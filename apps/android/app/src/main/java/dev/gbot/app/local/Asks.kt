package dev.gbot.app.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.PriorityQueue

/*
 * Bots asking bots: the rules shared with the server. See
 * docs/protocol/bot-asks.md; both sides are tested against
 * docs/protocol/bot-asks.fixtures.json.
 */

/** person → A (0) → B (1) → C (2), and C can't message anyone. */
const val MAX_ASK_DEPTH = 2

/** The messages one request from the person can set off, wherever they happen. */
const val MAX_ASKS_PER_ROOT = 6

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
 * One model runs at a time on a phone. Whoever waits takes the next turn by
 * priority (lower first), then in arrival order: the person's messages go
 * ahead of a round's answers, which go ahead of bots' asks.
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
