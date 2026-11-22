package dev.gbot.app.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

/*
 * Bots asking bots: the rules shared with the server. See
 * docs/protocol/bot-asks.md; both sides are tested against
 * docs/protocol/bot-asks.fixtures.json.
 */

/** person → A (0) → B (1) → C (2), and C can't message anyone. */
const val MAX_ASK_DEPTH = 2

/**
 * The messages one request from the person can set off, wherever they happen.
 * A runaway stop, not a working budget: a pair of bots going back and forth
 * over a real task spends several on each turn.
 */
const val MAX_ASKS_PER_ROOT = 12

/** Whether a bot at [depth] may send another message, given [sent] so far for its request. */
fun canMessage(depth: Int, sent: Int): Boolean = depth < MAX_ASK_DEPTH && sent < MAX_ASKS_PER_ROOT

private val ended = setOf("replied", "delivered", "failed", "needs_you", "expired", "cancelled")

fun askEnded(status: String?): Boolean = status in ended

fun roundDone(statuses: List<String?>): Boolean = statuses.all(::askEnded)

/** One ask in a round, as the sender gets it back. */
data class Answer(val name: String, val message: String, val status: String, val reply: String)

/** The one prompt that hands a round's answers back to the bot that asked. */
fun joinPrompt(answers: List<Answer>): String = buildString {
    append("Answers to what you asked your teammates:\n")
    for (a in answers) {
        val msg = clipRunes(a.message.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "), 200)
        append("- ${a.name}, asked \"$msg\":\n")
        for (line in answerText(a).split("\n")) {
            if (line.isBlank()) append("\n") else append("  ${line.trimEnd(' ', '\t')}\n")
        }
    }
    append("\nThe person can't read these answers: their card only shows that you messaged the bot. Use them to finish what the person asked, say what matters in your own words now, and don't ask these bots the same thing again.")
}

private fun answerText(a: Answer): String {
    val reply = clipRunes(a.reply.trim(), 2000)
    return when (a.status) {
        "replied" -> reply.ifEmpty { "had nothing to add." }
        "delivered" -> "had nothing to add."
        "failed" -> reply.ifEmpty { "couldn't answer." }
        "needs_you" -> "needs the person first; it asked them in its own chat."
        "cancelled" -> "was stopped before answering."
        else -> "didn't answer in time."
    }
}

private fun clipRunes(s: String, n: Int): String {
    val cps = s.codePoints().toArray()
    if (cps.size <= n) return s
    return String(cps, 0, n).trim() + "…"
}

/** What the receiving bot is given. */
fun askPrompt(from: String, message: String, expect: String): String =
    if (expect == "tell") {
        "Message from $from, another of the person's bots, passing something on. Nothing you write goes back to $from. " +
            "If it asks you to do something, do it; otherwise reply with just $NO_REPLY.\n\n$message"
    } else {
        "Message from $from, another of the person's bots. $from is waiting for your answer: write it as your reply here, " +
            "and it goes back to $from, not to the person. Answer the question; don't ask $from anything back.\n\n$message"
    }

/**
 * One queue per chat, like the server's per-conversation workers: a chat's
 * messages run in order, and different chats run side by side. A lane is a
 * fair mutex, so waiters go in arrival order.
 */
class Lanes {
    private val lanes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> run(chat: String, block: suspend () -> T): T =
        lanes.getOrPut(chat) { Mutex() }.withLock { block() }
}

/**
 * The phone's own model generates for one run at a time; runs on a
 * provider's model never wait here. A run takes the turn only while the
 * model is working, not while its tools run or it waits on the person.
 * Whoever waits takes the next turn by priority (lower first), then in
 * arrival order: the person's messages go ahead of a round's answers,
 * which go ahead of bots' asks.
 */
class Turns {
    private class Waiter(val priority: Int, val seq: Long, val go: CompletableDeferred<Unit> = CompletableDeferred())

    private val lock = Any()
    private var busy = false
    private var seq = 0L
    private val waiting = PriorityQueue<Waiter>(compareBy<Waiter>({ it.priority }, { it.seq }))

    suspend fun <T> take(priority: Int, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            release()
        }
    }

    /** Take the turn only if nobody has it or is waiting for it. */
    fun tryTake(): Boolean = synchronized(lock) {
        if (busy || waiting.isNotEmpty()) false else { busy = true; true }
    }

    fun release() = synchronized(lock) {
        // The turn passes straight to the next waiter; busy stays set.
        val next = waiting.poll()
        if (next == null) busy = false else next.go.complete(Unit)
    }

    private suspend fun acquire(priority: Int) {
        val w = synchronized(lock) {
            if (!busy && waiting.isEmpty()) {
                busy = true
                return
            }
            Waiter(priority, seq++).also { waiting.add(it) }
        }
        try {
            w.go.await()
        } catch (e: CancellationException) {
            // Cancelled while waiting: leave the line. If the turn was
            // already handed over, pass it on.
            val handed = synchronized(lock) { !waiting.remove(w) }
            if (handed) release()
            throw e
        }
    }
}

/** How many of something each key has going, such as a bot's runs. */
class Counts {
    private val n = ConcurrentHashMap<String, Int>()

    /** One more for [k]; the new count. */
    fun up(k: String): Int = n.merge(k, 1, Int::plus) ?: 1

    /** One fewer for [k]; what is left. */
    fun down(k: String): Int = n.computeIfPresent(k) { _, v -> if (v <= 1) null else v - 1 } ?: 0

    fun of(k: String): Int = n[k] ?: 0
}

/**
 * One heavy thing opened for a key, such as the model loaded from one file.
 * Up to [limit] holders share the open key at once; a different key waits
 * until nobody holds the open one, then closes it and opens its own.
 *
 * Waiters are served strictly by priority (lower first), then in arrival
 * order, and the one at the head isn't passed: a run waiting for another
 * model isn't starved by a stream of runs on the current one, and the
 * person's messages go first. The one exception is bounded: while the runs
 * that stood [aside] when the head got stuck are still away, runs on the
 * open model may use its places past the head, since it couldn't go anyway. A hold taken inside a hold of the same key
 * (task_verify within a run) joins it at once and counts for nothing.
 *
 * A holder that has to wait on something slow and outside its control (the
 * person's answer, another bot's browser) steps [aside]: it gives up its
 * place under [limit] but keeps the key open, and comes back ahead of
 * everyone waiting once a place is free.
 */
class Leased<T : Any>(
    private val open: suspend (String) -> T,
    private val close: (T) -> Unit,
    private val limit: Int = Int.MAX_VALUE,
) {
    private class Waiter(val key: String, val priority: Int, val seq: Long, val back: Held? = null, val go: CompletableDeferred<Unit> = CompletableDeferred()) {
        // For a head stuck behind another key: who stood aside when it got stuck.
        var stuckBehind: Set<Held>? = null
    }
    private class Held(val owner: Leased<*>, val heldKey: String) : AbstractCoroutineContextElement(Held) {
        companion object : CoroutineContext.Key<Held>

        // Standing aside: counted in [aside], not [holders].
        @Volatile var away = false
    }
    private val order = compareBy<Waiter>({ it.priority }, { it.seq })

    private val lock = Mutex()
    private var value: T? = null
    @Volatile var key: String? = null
        private set
    private var holders = 0
    // Holders standing aside: they keep the key open but take no place.
    private val standing = HashSet<Held>()
    private var seq = 0L
    private val waiting = PriorityQueue(order)

    val isOpen: Boolean get() = key != null

    suspend fun <R> hold(k: String, priority: Int = 0, onWait: () -> Unit = {}, block: suspend (T) -> R): R {
        coroutineContext[Held]?.let { h ->
            if (h.owner === this && h.heldKey == k) return block(lock.withLock { value!! })
        }
        val v = acquire(k, priority, onWait)
        val h = Held(this, k)
        try {
            return withContext(h) { block(v) }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (h.away) standing.remove(h) else holders--
                    dispatch()
                }
            }
        }
    }

    /**
     * Run [block], a wait, without taking a place, when called inside a
     * hold; the key stays open meanwhile. Outside a hold it just runs.
     */
    suspend fun <R> aside(block: suspend () -> R): R {
        val h = coroutineContext[Held]?.takeIf { it.owner === this && !it.away } ?: return block()
        lock.withLock {
            holders--
            standing.add(h)
            h.away = true
            dispatch()
        }
        val r = try {
            block()
        } catch (e: CancellationException) {
            // Stopped while waiting: no coming back for a place. The run lets
            // go of the key as it unwinds, so it holds up nobody.
            throw e
        } catch (e: Throwable) {
            comeBack(h)
            throw e
        }
        comeBack(h)
        return r
    }

    private suspend fun comeBack(h: Held) {
        val w = lock.withLock {
            if (holders < limit) {
                standing.remove(h)
                holders++
                h.away = false
                null
            } else {
                // First in line; served as soon as a place is free.
                Waiter(h.heldKey, Int.MIN_VALUE, seq++, back = h).also { waiting.add(it); dispatch() }
            }
        }
        if (w == null) return
        try {
            w.go.await()
        } catch (e: CancellationException) {
            // Stopped on the way back: leave the line. Whether or not the
            // place was already handed over, [Held.away] says which count
            // the hold gives back as it unwinds.
            withContext(NonCancellable) { lock.withLock { waiting.remove(w); dispatch() } }
            throw e
        }
    }

    /** Close it if nobody holds it or waits for it. True when nothing is open afterwards. */
    suspend fun closeIfFree(): Boolean = lock.withLock {
        if (holders > 0 || standing.isNotEmpty()) return@withLock false
        shut()
        true
    }

    private fun fits(w: Waiter) = if (w.back != null) holders < limit else fits(w.key)

    private fun fits(k: String) = (holders == 0 && standing.isEmpty()) || (key == k && holders < limit)

    /** Hand the open key to whoever is next, in order, as far as it fits. */
    private fun dispatch() {
        while (true) {
            val w = next() ?: return
            waiting.remove(w)
            val back = w.back
            if (back != null) {
                standing.remove(back)
                holders++
                back.away = false
            } else {
                grant(w.key)
            }
            w.go.complete(Unit)
        }
    }

    /**
     * The head of the line, if it fits. A head waiting for another model
     * can't go while a run stands aside for the person, since that run's
     * conversation keeps the open model. Runs on the open model may fill its
     * places past the head only while the runs that stood aside when it got
     * stuck are still away; runs that go aside later don't keep that open,
     * so a stream of overlapping asides can't hold the head off for good.
     */
    private fun next(): Waiter? {
        val head = waiting.peek() ?: return null
        if (fits(head)) return head
        if (head.back != null || head.key == key) return null
        val stuck = head.stuckBehind ?: standing.toSet().also { head.stuckBehind = it }
        if (stuck.none { it in standing }) return null
        return waiting.sortedWith(order).firstOrNull { it.key == key && fits(it) }
    }

    private fun grant(k: String) {
        if (key != k) {
            shut()
            key = k
        }
        holders++
    }

    private suspend fun acquire(k: String, priority: Int, onWait: () -> Unit): T {
        val w = lock.withLock {
            if (waiting.isEmpty() && fits(k)) {
                grant(k)
                null
            } else {
                // It may go ahead of a head that doesn't fit, and fit itself.
                Waiter(k, priority, seq++).also { waiting.add(it); dispatch() }
            }
        }
        if (w != null) {
            // Served at once if it went ahead of a head that doesn't fit.
            if (!w.go.isCompleted) onWait()
            try {
                w.go.await()
            } catch (e: CancellationException) {
                // Cancelled while waiting: leave the line, or give back
                // what was already handed over.
                withContext(NonCancellable) {
                    lock.withLock {
                        if (!waiting.remove(w)) holders--
                        dispatch()
                    }
                }
                throw e
            }
        }
        // The first holder of a newly granted key opens it; the rest find it open.
        return try {
            lock.withLock { value ?: open(k).also { value = it } }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                lock.withLock {
                    holders--
                    if (holders == 0 && value == null) key = null
                    dispatch()
                }
            }
            throw e
        }
    }

    private fun shut() {
        value?.let { runCatching { close(it) } }
        value = null
        key = null
    }
}

object Priority {
    const val PERSON = 0
    const val ANSWERS = 1
    const val BOTS = 2
}

/**
 * What a running app keeps about rounds and requests, on top of the
 * exchange cards (which are the durable record): which rounds are still
 * open, which were handed back, and a trace per request from the person.
 */
class AskBook(private val clock: () -> Long = System::currentTimeMillis) {
    private class Trace(val start: Long) {
        var seen = start
        var runs = 0
        var calls = 0
        var asks = 0
        var depth = 0
    }

    private val open = HashSet<String>()
    private val joined = HashSet<String>()
    private val roots = HashMap<String, Trace>()

    @Synchronized private fun trace(root: String): Trace = roots.getOrPut(root) { Trace(clock()) }.also { it.seen = clock() }

    /** A run starts: counted against its request. */
    @Synchronized fun startRun(root: String, depth: Int) {
        val now = clock()
        roots.entries.removeAll { now - it.value.seen > 6 * 60 * 60 * 1000L }
        trace(root).apply { runs++; this.depth = maxOf(this.depth, depth) }
    }

    /** One call to the model. */
    @Synchronized fun call(root: String) { trace(root).calls++ }

    @Synchronized fun sent(root: String): Int = roots[root]?.asks ?: 0

    /** Reserve one message for a request, if it has any left. */
    @Synchronized fun take(root: String, depth: Int): Boolean {
        val t = trace(root)
        if (!canMessage(depth, t.asks)) return false
        t.asks++
        t.depth = maxOf(t.depth, depth + 1)
        return true
    }

    @Synchronized fun open(round: String) { open.add(round) }
    @Synchronized fun close(round: String) { open.remove(round) }
    @Synchronized fun isOpen(round: String): Boolean = round in open
    @Synchronized fun wasJoined(round: String): Boolean = round in joined

    /** True for the one caller that gets to hand a round back. */
    @Synchronized fun claimJoin(round: String): Boolean = joined.add(round)

    @Synchronized fun traceLine(root: String): String {
        val t = roots[root] ?: return ""
        return "root=$root runs=${t.runs} calls=${t.calls} asks=${t.asks} depth=${t.depth} ms=${clock() - t.start}"
    }
}

fun newId(prefix: String): String = prefix + java.util.UUID.randomUUID().toString().replace("-", "").take(12)

/** The largest picture the phone keeps from a tool, as on the server. */
const val MAX_PICTURE = 8 shl 20

/** A type as a person would name it: "PNG", "PDF". */
fun kindOf(mime: String): String {
    val m = mime.trim().lowercase().substringBefore(';')
    if (m.isEmpty()) return "file"
    val sub = m.substringAfter('/', "").ifEmpty { return m }
    return sub.removePrefix("x-").substringBefore('+').uppercase()
}

/** A byte count in words. */
fun sizeOf(n: Int): String = when {
    n >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB", n.toDouble() / (1 shl 20))
    n >= 1 shl 10 -> "${n / (1 shl 10)} KB"
    else -> "$n bytes"
}

/** The file ending for a picture's type, so a saved file opens elsewhere too. */
fun extensionOf(mime: String): String = when (kindOf(mime).lowercase()) {
    "png" -> ".png"
    "jpeg", "jpg" -> ".jpg"
    "gif" -> ".gif"
    "webp" -> ".webp"
    "svg" -> ".svg"
    else -> ".img"
}

/**
 * Whether a type is a picture gbot keeps and draws. SVG is left out on
 * purpose: it is a document that can carry script, as on the server.
 */
fun showableImage(mime: String): Boolean = mime.trim().lowercase().substringBefore(';') in setOf(
    "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/bmp",
)
