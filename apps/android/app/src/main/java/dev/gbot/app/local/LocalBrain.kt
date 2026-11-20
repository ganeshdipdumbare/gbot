package dev.gbot.app.local

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import dev.gbot.app.live.Events
import dev.gbot.app.net.Bot
import dev.gbot.app.net.ComputerStatus
import dev.gbot.app.net.Group
import dev.gbot.app.net.Message as GMessage
import dev.gbot.app.net.Presence
import dev.gbot.app.net.QueueItem
import dev.gbot.app.net.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tools that drive the bot's browser or computer, not just the web. */
internal fun usesComputer(name: String) = name in COMPUTER_TOOLS

private val COMPUTER_TOOLS = setOf(
    "browser_open", "browser_read", "browser_click", "browser_fill", "browser_enter",
    "browser_back", "browser_forward", "browser_reload", "browser_scroll", "browser_wait",
    "open_in_chrome", "vault_type",
)

/**
 * Browser tool names models bring from elsewhere — the server's own
 * (browser_navigate, browser_snapshot), Playwright's, or a plain guess —
 * mapped to the phone's. Without this the call fails as an unknown tool
 * while the chat says the bot is browsing, and its window stays empty.
 */
internal fun canonicalTool(name: String): String = TOOL_ALIASES[name.trim().lowercase()] ?: name.trim()

private val TOOL_ALIASES = mapOf(
    "browser_navigate" to "browser_open", "browser_goto" to "browser_open", "browser_go_to" to "browser_open",
    "browser_visit" to "browser_open", "browser_open_url" to "browser_open", "browser_load" to "browser_open",
    "browser_search" to "browser_open", "open_url" to "browser_open", "navigate" to "browser_open",
    "browser_snapshot" to "browser_read", "browser_get_content" to "browser_read", "browser_get_text" to "browser_read",
    "browser_content" to "browser_read", "browser_view" to "browser_read", "browser_page" to "browser_read",
    "browser_type" to "browser_fill", "browser_input" to "browser_fill",
    "browser_press" to "browser_enter", "browser_submit" to "browser_enter",
    "browser_go_back" to "browser_back", "browser_go_forward" to "browser_forward", "browser_refresh" to "browser_reload",
)

/**
 * The argument names those borrowed calls bring — the server's
 * browser_type {ref, text}, a search's {query} — under the phone's own,
 * so the approval card, the evidence and the tool all read the same
 * url, id and value.
 */
internal fun canonicalArgs(name: String, args: JsonObject): JsonObject {
    fun pick(vararg keys: String): JsonPrimitive? = keys.firstNotNullOfOrNull { k ->
        (args[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content.isNotBlank() }
    }
    val id = { pick("id", "ref", "element", "index") }
    val wanted: Map<String, JsonPrimitive?> = when (name) {
        "browser_open", "open_in_chrome" -> mapOf("url" to pick("url", "query", "q", "link", "address", "href"))
        "browser_click", "browser_enter" -> mapOf("id" to id())
        "browser_fill" -> mapOf("id" to id(), "value" to pick("value", "text", "input"))
        else -> emptyMap()
    }
    val fill = wanted.filter { (k, v) -> v != null && args[k] != v }.mapValues { it.value!! }
    return if (fill.isEmpty()) args else JsonObject(args + fill)
}

/**
 * The bot's mind, on the phone. One run: think, use tools, answer. The
 * conversation is rebuilt from the last few turns each time, so a run can
 * be stopped or the app closed without losing the chat. Groups reuse the
 * same run per bot, with the shared transcript and the same @mention rules
 * as the server.
 */
class LocalBrain(
    private val ctx: Context,
    private val db: LocalDb,
    private val api: LocalApi,
    private val events: Events,
    private val browser: BrowserController,
    private val scope: CoroutineScope,
) {
    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var engineKey: String? = null

    // One model runs at a time on a phone; other runs wait their turn in
    // the same queue the chat already draws, the person's messages first.
    private val turns = Turns()
    private val book = AskBook()
    private val recovered = java.util.concurrent.atomic.AtomicBoolean(false)
    private var idleJob: Job? = null
    private val jobs = ConcurrentHashMap<String, Job>()
    private val byQid = ConcurrentHashMap<String, Job>()
    private val queues = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<QueueItem>>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val stopped = ConcurrentHashMap.newKeySet<String>()
    private val browsing = ConcurrentHashMap.newKeySet<String>()
    // What the running run agreed and did, for task_verify. One run holds
    // the turn at a time, so one is enough.
    @Volatile private var proof: RunBook? = null
    @Volatile private var warnedCpu = false

    fun launch(bot: Bot, prompt: String) = start(Work(bot.id, prompt))

    fun launchRoutine(bot: Bot, prompt: String) = start(Work(bot.id, prompt, kind = "routine"))

    fun launchSlack(bot: Bot, prompt: String, target: SlackTarget) =
        start(Work(bot.id, prompt, kind = "slack", slack = target))

    fun launchGroup(group: Group, bot: Bot, prompt: String, depth: Int) =
        start(Work(bot.id, prompt, groupId = group.id, depth = depth, kind = "group"))

    fun queueFor(chat: String): List<QueueItem> = queues[chat].orEmpty().toList()

    /** Something is running or waiting its turn. */
    fun busy(): Boolean = jobs.isNotEmpty()

    fun chatOf(qid: String): String? = queues.entries.find { (_, list) -> list.any { it.id == qid } }?.key

    fun queueAction(qid: String, action: String) {
        val chat = queues.entries.find { (_, list) -> list.any { it.id == qid } }?.key ?: return
        val list = queues[chat] ?: return
        val item = list.find { it.id == qid } ?: return
        when (action) {
            "cancel" -> {
                list.remove(item)
                stopped.add(chat)
                byQid.remove(qid)?.cancel()
                jobs.remove(qid)
                if (!chat.startsWith("g:")) emitPresence(chat, null, "idle")
                emitQueue(chat)
            }
            "pause" -> {
                paused.add(qid)
                list[list.indexOf(item)] = item.copy(status = "paused")
                emitQueue(chat)
            }
            "resume" -> {
                paused.remove(qid)
                list[list.indexOf(item)] = item.copy(status = "queued")
                emitQueue(chat)
            }
        }
    }

    fun clearQueue(chat: String) {
        stopped.add(chat)
        queues[chat]?.map { it.id }?.forEach { qid ->
            byQid.remove(qid)?.cancel()
            jobs.remove(qid)
        }
        queues[chat]?.clear()
        if (!chat.startsWith("g:")) emitPresence(chat, null, "idle")
        emitQueue(chat)
    }

    private fun start(work: Work, qid: String = "q_" + UUID.randomUUID().toString().take(8)) {
        // A new message keeps the model warm.
        idleJob?.cancel()
        idleJob = null
        val w = if (work.root.isEmpty()) work.copy(root = newId("rt_")) else work
        val chat = w.groupId?.let { "g:$it" } ?: w.botId
        stopped.remove(chat)
        // The person's own runs are saved until they finish, so closing the
        // app doesn't lose them. Bots' messages come back from their cards.
        if (w.durable) db.putQueued(qid, now(), json.encodeToString(Work.serializer(), w))
        queues.getOrPut(chat) { java.util.concurrent.CopyOnWriteArrayList() }.add(QueueItem(qid, queueLabel(w), "queued"))
        emitQueue(chat)
        // The phone kills a cached process, so a run the person walks away
        // from would end with no reply and no alert. Android keeps work
        // alive only for a foreground service; it lets go once nothing is
        // running. Started here, while the app is still in front, because
        // the system refuses a foreground service started from the back.
        dev.gbot.app.live.LocalService.work(ctx)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // A paused message waits outside the line, so others go ahead.
            while (qid in paused && isActive) delay(300)
            turns.take(w.priority) {
                doneQueued(chat, qid)
                val bot = db.bot(w.botId) ?: return@take
                val group = w.groupId?.let { db.group(it) ?: return@take }
                if (w.durable) db.putQueued(qid, now(), json.encodeToString(Work.serializer(), w.copy(tries = w.tries + 1)))
                runCatching { run(bot, group, w) }
                    .onFailure {
                        if (it is CancellationException) {
                            // Stop is silent; any other cancellation is a fault.
                            if (!stopped.remove(chat)) report(bot, group, it)
                            throw it
                        }
                        report(bot, group, it)
                    }
            }
            doneQueued(chat, qid)
        }
        jobs[qid] = job
        byQid[qid] = job
        job.invokeOnCompletion {
            if (jobs[qid] === job) jobs.remove(qid)
            if (byQid[qid] === job) byQid.remove(qid)
            if (w.durable) runCatching { db.removeQueued(qid) }
            doneQueued(chat, qid)
            scheduleIdleUnload()
        }
        job.start()
    }

    private fun queueLabel(w: Work): String = when (w.kind) {
        "ask", "tell" -> "Message from ${w.from?.let { db.bot(it)?.name } ?: "a bot"}"
        "join" -> "Answers from your teammates"
        else -> w.prompt.take(120)
    }

    /**
     * Pick up what the app was doing when it closed: the person's queued
     * messages, messages between bots nobody answered, and rounds whose
     * answers never went back. Runs once per process.
     */
    fun recover() {
        if (!recovered.compareAndSet(false, true)) return
        for ((id, body) in db.queued()) {
            val w = runCatching { json.decodeFromString(Work.serializer(), body) }.getOrNull()
            // Two tries is enough: a run that keeps killing the app stops here.
            if (w == null || w.tries >= 2) { db.removeQueued(id); continue }
            start(w, id)
        }
        val cards = db.recentExchangeCards(java.time.Instant.now().minusSeconds(2 * 24 * 3600).toString())
        var asks = 0
        for (inn in cards) {
            if (inn.botId != inn.str("to") || inn.str("status") !in setOf("sent", "working")) continue
            val out = cards.find { it.id != inn.id && it.str("ask") == inn.str("ask") } ?: continue
            val sender = inn.str("from")?.let { db.bot(it) }
            if (sender == null || (inn.str("tries")?.toIntOrNull() ?: 0) >= 2) {
                listOf(out, inn).forEach { patchCard(it, mapOf("status" to "failed", "reply" to "This message was interrupted.")) }
                continue
            }
            val expect = inn.str("expect") ?: "ask"
            start(
                Work(
                    inn.botId.orEmpty(), askPrompt(sender.name, inn.str("message").orEmpty(), expect),
                    depth = inn.str("depth")?.toIntOrNull() ?: 1, kind = expect, root = inn.str("root").orEmpty(),
                    exchange = listOf(out.id, inn.id), from = sender.id,
                    // Whether the sender had read outside text wasn't kept
                    // across the restart, so assume it had (as the server does).
                    untrusted = true,
                ),
            )
            asks++
        }
        val rounds = cards.filter { it.botId == it.str("from") && it.str("joined") != "true" }.mapNotNull { it.str("round") }.distinct()
        rounds.forEach(::closeRound)
        if (asks + rounds.size > 0) log("resumed $asks asks, ${rounds.size} rounds")
    }

    private fun doneQueued(chat: String, qid: String) {
        val list = queues[chat] ?: return
        if (list.none { it.id == qid }) return
        list.removeAll { it.id == qid }
        paused.remove(qid)
        emitQueue(chat)
    }

    private fun emitQueue(chat: String) {
        val items = JsonArray(queues[chat].orEmpty().map { json.encodeToJsonElement(QueueItem.serializer(), it) })
        events.handle(buildJsonObject {
            put("type", "queue")
            if (chat.startsWith("g:")) put("groupId", chat.drop(2)) else put("botId", chat)
            put("data", items)
        })
    }

    fun isBrowsing(botId: String): Boolean = botId in browsing

    /** What is held in memory right now, as a model id, or null. */
    fun loadedModelId(): String? = engineKey?.let { LocalModels.id(File(it)) }

    /** The idle window, from the person's setting. */
    fun idleMinutes(): Int = ModelMemory.minutesOf(db.get("modelIdle"))

    fun setIdleMinutes(minutes: Int) {
        db.set("modelIdle", minutes.toString())
        if (engine != null) scheduleIdleUnload()
    }

    /** Close the native model only when no reply is running. */
    suspend fun unloadIfIdle(): Boolean {
        if (!turns.tryTake()) return false
        return try {
            engineLock.withLock { closeEngine() }
            true // already unloaded counts as done
        } finally {
            turns.release()
        }
    }

    suspend fun unloadNow(): Boolean {
        idleJob?.cancel()
        idleJob = null
        return unloadIfIdle()
    }

    /** Stop everything and let go of the model; used when moving to a server. */
    suspend fun shutdown() {
        idleJob?.cancel()
        idleJob = null
        jobs.keys.toList().forEach { qid -> jobs.remove(qid)?.cancel() }
        byQid.values.forEach { it.cancel() }
        byQid.clear()
        queues.keys.toList().forEach { clearQueue(it) }
        turns.take(Priority.PERSON) { engineLock.withLock { closeEngine() } }
    }

    private fun closeEngine(): Boolean {
        val had = engine != null
        runCatching { engine?.close() }
        engine = null
        engineKey = null
        return had
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = null
        val minutes = idleMinutes()
        if (minutes <= 0) return
        idleJob = scope.launch(Dispatchers.IO) {
            delay(minutes * 60_000L)
            unloadIfIdle()
        }
    }

    fun cancel(chat: String) {
        clearQueue(chat)
        if (!chat.startsWith("g:")) emitPresence(chat, null, "idle")
    }

    fun cancelGroup(groupId: String) = cancel("g:$groupId")

    private suspend fun run(bot: Bot, group: Group?, w: Work) {
        val prompt = w.prompt
        val model = api.resolveModel(bot.model)
        log("run bot=${bot.name} kind=${w.kind} model=${bot.model.ifBlank { "default" }} group=${group?.id ?: "-"}")
        book.startRun(w.root, w.depth)
        // The message this run answers, if another bot sent it: the answer
        // goes on its two cards, not into this chat.
        val exchange = w.exchange.mapNotNull { db.message(it) }
        var status = "failed"
        var outcome = "${bot.name} didn't reply."
        // A reply the person can actually see: what earns a "done" alert.
        var landed = false
        if (exchange.isNotEmpty()) {
            val tries = (exchange.last().str("tries")?.toIntOrNull() ?: 0) + 1
            exchange.forEach { patchCard(it, mapOf("status" to "working", "tries" to tries)) }
        }
        w.joinRound?.let(::markJoined)
        // The asks this run sends go back together once it ends.
        val round = RunRound()
        val runProof = RunBook().also { proof = it }
        // A Slack message is outside text from the start, and so is a
        // message from a bot whose own run had read some.
        val taint = RunTaint(w.untrusted || w.kind == "slack")
        emitPresence(bot.id, group, "thinking")
        try {
            fun stage(detail: String) = emitPresence(bot.id, group, "working", detail)

            // In a group the teammates are @mentioned instead, and a bot
            // answering a message can't message the sender back: its
            // answer is the reply. Depth decides whether the tool exists;
            // the request's message budget is checked when one is sent, so
            // a bot that has spent it is refused in words instead of
            // losing the tool and inventing the conversation.
            val peers = if (group != null || w.depth >= MAX_ASK_DEPTH) emptyList()
            else db.bots().filter { it.id != bot.id && it.id != w.from }
            val tools = LocalTools(
                ctx, db, browser, bot.id,
                taint = taint,
                skills = api.localSkills(),
                secret = { name -> api.secret(name) },
                schedule = { name, p, cron -> api.scheduleFromChat(bot.id, name, p, cron) },
                peers = peers,
                messageBot = { target, text, expect -> messageBot(bot, target, text, expect, w, round, taint.on) },
                roster = { db.bots() },
                createBot = { name, title -> api.createBot(name, title, dev.gbot.app.net.Look()) },
                changeBot = { id, patch -> api.updateBot(id, patch) },
                archiveBot = { id -> api.archiveBot(id) },
                // Only the person's own message can vouch for a link; a teammate bot, Slack or a routine can't.
                knownUrls = if (w.kind == "person" && w.from == null) WebLookup.linksIn(prompt) else emptySet(),
                host = object : LocalTools.ToolHost {
                    override suspend fun ask(question: String, options: List<String>) = askPerson(bot, group, question, options)
                    override suspend fun routines() = db.routines(bot.id)
                    override suspend fun setRoutine(id: String, active: Boolean) { api.updateRoutine(bot.id, id, active) }
                    override suspend fun deleteRoutine(id: String) { api.deleteRoutine(bot.id, id) }
                    override suspend fun runRoutine(id: String) { api.runRoutine(bot.id, id) }
                    override fun watching(host: String) {
                        event(
                            bot, group, "Browsing ${host.ifEmpty { "the web" }} — tap to watch",
                            buildJsonObject { put("kind", "browser"); put("host", host) },
                        )
                    }
                    override fun contract(goal: String, checks: List<String>): String {
                        if (goal.isEmpty() || checks.isEmpty()) return "Give the goal and at least one check."
                        runProof.contract = Contract(goal, checks)
                        return "Contract set with ${checks.size} checks. Do the work, then call task_verify before saying it is done."
                    }
                    override suspend fun verify(): String {
                        val c = runProof.contract ?: return "Agree on a task_contract first."
                        book.call(w.root)
                        val raw = oneShot(model, "You are an independent verifier.", verifyPrompt(prompt, c, runProof.evidence()), 1200)
                        return receipt(raw).also { runProof.receipt = it }
                    }
                },
            )
            val mcpTools = withTimeoutOrNull(MCP_TIMEOUT_MS) { api.mcpSchemas() }.orEmpty()
            val history = historyFor(bot, group, prompt)

            // A message from the person starts with an internal reading of
            // it: what is being asked and how to go about it. Time-boxed,
            // and skipped quietly if the model will not produce one. A
            // bot's ask is already scoped by the bot that sent it, and a
            // round's answers only need finishing, so those skip it: on a
            // phone every model call waits in the same line.
            val plan = if (!w.plans || !worthPlanning(prompt)) "" else {
                stage("Planning")
                book.call(w.root)
                withTimeoutOrNull(PLAN_TIMEOUT_MS) {
                    when (model) {
                        is BrainModel.Local -> planLocal(model.file, bot, group, prompt, history)
                        is BrainModel.Remote -> planRemote(model, bot, group, prompt, history)
                        null -> null
                    }
                }.let { cleanPlan(it.orEmpty()) }
            }
            if (plan.isNotEmpty()) log("plan ${plan.length} chars")

            val system = systemPrompt(bot, group, peers) + planBlock(plan)

            book.call(w.root)
            val text = withTimeoutOrNull(RUN_TIMEOUT_MS) {
                when (model) {
                    is BrainModel.Local -> {
                        log("local ${model.file.name} history=${history.size} tools=${tools.schemas.size + mcpTools.size}")
                        runLocal(model.file, system, history, prompt, tools, mcpTools, bot, group, ::stage)
                    }
                    is BrainModel.Remote -> {
                        log("provider ${model.provider.name} ${model.name}")
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            api.providers.run(
                                model.provider, model.name, system, groupHistory(history, group, bot), prompt, tools.schemas + mcpTools,
                                onText = {},
                                execute = { call -> executeTool(tools, bot, group, call.name, call.arguments.toJson(), ::stage) },
                            )
                        } ?: throw IllegalStateException(
                            "${model.provider.name} was still going after ${PROVIDER_TIMEOUT_MS / 60000} minutes, so I stopped. " +
                                "Check the endpoint and key in Settings → Models.",
                        )
                    }
                    null -> if (api.providers.list().isNotEmpty()) {
                        "Your provider isn't offering any models yet. Open Settings → Models → Providers and press refresh on it."
                    } else {
                        "There is no model on this phone yet. Open Settings → Models and download one, or connect a provider."
                    }
                }
            }

            val content = when {
                text == null -> "I stopped — that took longer than ${RUN_TIMEOUT_MS / 60000} minutes. The model may be too big for this phone; try a smaller one in Settings → Models."
                text.isBlank() -> "(no answer)"
                else -> text
            }
            log("reply ${content.length} chars")
            // The sentinel is for the machinery, not the chat: a bot with
            // nothing to say says nothing at all.
            // A checked deliverable carries its receipt, even if the model forgot to show it.
            val said = cleanReply(content).let { a ->
                if (runProof.receipt.isEmpty() || a.isEmpty() || a.contains("**Verification**")) a else a + "\n\n" + runProof.receipt
            }
            if (exchange.isNotEmpty()) {
                status = when {
                    text == null -> "failed"
                    w.kind == "tell" || said.isEmpty() -> "delivered"
                    else -> "replied"
                }
                outcome = said
            } else {
                if (said.isNotEmpty()) reply(bot, group, said)
                // Asked from Slack: the answer goes back into that thread too.
                if (said.isNotEmpty()) w.slack?.let { t -> runCatching { api.slack.post(t, said) } }
                if (group != null && w.depth < 3) handoff(group, bot, said, w, taint.on)
                landed = said.isNotEmpty()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                status = "cancelled"
                outcome = "${bot.name} was stopped before replying."
            } else {
                outcome = "${bot.name} couldn't reply: ${e.message ?: e::class.simpleName}"
            }
            throw e
        } finally {
            if (exchange.isNotEmpty()) settle(exchange, status, outcome)
            round.id?.let(::closeRound)
            if (proof === runProof) proof = null
            browsing.remove(bot.id)
            emitComputer(bot.id, false)
            // In a group the reply itself is the alert; its own chat has
            // nothing else to show for it, so settle through "done".
            if (landed && group == null) settleDone(bot) else emitPresence(bot.id, group, "idle")
        }
    }

    /** Wake teammates this reply @mentions, like the server's group handoff. */
    private fun handoff(group: Group, from: Bot, reply: String, w: Work, tainted: Boolean) {
        if (!reply.contains("@")) return
        for (id in group.botIds) {
            if (id == from.id) continue
            val mate = db.bot(id) ?: continue
            if (Mentions.matches(reply, mate.name)) {
                start(
                    Work(
                        mate.id, "Continue the group task. ${from.name} mentioned you: $reply",
                        groupId = group.id, depth = w.depth + 1, kind = "group", root = w.root,
                        // A reply written after outside text carries it on.
                        untrusted = tainted,
                    ),
                )
            }
        }
    }

    /** What the bot is about to be asked, read before it answers. */
    private fun planSystem(bot: Bot, group: Group?): String = buildString {
        append("You are ${bot.name}.")
        if (group != null) append(" You are in the group chat “${group.name}”.")
        append(
            " One message is about to arrive. Read it in context and decide how you will handle it, " +
                "but do not answer it yet.",
        )
    }

    /** The one message plus just enough of what came before it. */
    private fun planAsk(prompt: String, history: List<GMessage>, group: Group?, bot: Bot): String = buildString {
        val before = history.takeLast(2)
        if (before.isNotEmpty()) {
            appendLine("Just before this:")
            for (m in before) {
                val who = when {
                    m.role == "user" -> "The person"
                    group != null && m.botId != null && m.botId != bot.id -> db.bot(m.botId)?.name ?: "A teammate"
                    else -> "You"
                }
                appendLine("- $who: ${m.content.take(140)}")
            }
            appendLine()
        }
        appendLine("The message to handle:")
        appendLine(prompt.take(1200))
        appendLine()
        appendLine("Write only the plan, at most 3 short lines:")
        appendLine("WANT: what they actually want, in one line")
        appendLine("STEPS: 1) ... 2) ...")
        append("Do not answer the message, do not greet, and add no other lines.")
    }

    private suspend fun planLocal(
        file: File,
        bot: Bot,
        group: Group?,
        prompt: String,
        history: List<GMessage>,
    ): String = withContext(Dispatchers.IO) {
        val e = engine(file)
        val config = ConversationConfig(
            systemInstruction = Contents.of(planSystem(bot, group)),
            initialMessages = emptyList(),
            tools = emptyList(),
            automaticToolCalling = false,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3, seed = 0),
            maxOutputToken = 160,
        )
        e.createConversation(config).use { conv ->
            val sb = StringBuilder()
            conv.sendMessageAsync(Message.user(planAsk(prompt, history, group, bot))).collect { sb.append(textOf(it)) }
            sb.toString()
        }
    }

    private suspend fun planRemote(
        model: BrainModel.Remote,
        bot: Bot,
        group: Group?,
        prompt: String,
        history: List<GMessage>,
    ): String = api.providers.run(
        model.provider, model.name, planSystem(bot, group), emptyList(),
        planAsk(prompt, history, group, bot), emptyList(),
        onText = {},
        execute = { "" },
    )

    private suspend fun runLocal(
        file: File,
        system: String,
        history: List<GMessage>,
        prompt: String,
        tools: LocalTools,
        mcpTools: List<ToolSchema>,
        bot: Bot,
        group: Group?,
        onStage: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val e = engine(file)
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            initialMessages = history.map { m ->
                val t = m.content.take(700)
                when {
                    group != null && m.role == "bot" && m.botId != bot.id ->
                        Message.user("${db.bot(m.botId.orEmpty())?.name ?: "Someone"}: $t")
                    m.role == "user" -> Message.user(t)
                    else -> Message.model(t)
                }
            },
            tools = (tools.schemas + mcpTools).map { schema ->
                tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString(): String = buildJsonObject {
                        put("name", schema.name)
                        put("description", schema.description)
                        put("parameters", schema.parameters)
                    }.toString()

                    // Never called: the run executes tools itself so the
                    // person can approve them first.
                    override fun execute(paramsJsonString: String): String = "{}"
                })
            },
            automaticToolCalling = false,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7, seed = 0),
            maxOutputToken = 2048,
        )

        val schemas = tools.schemas + mcpTools
        e.createConversation(config).use { conv ->
            var next: Message = Message.user(prompt.take(4000))
            var answer = ""
            var rescues = 0
            repeat(MAX_STEPS) { step ->
                // The flow emits deltas, so the step's text is their sum.
                onStage("Thinking")
                val text = StringBuilder()
                var calls: List<ToolCall> = emptyList()
                val failure = runCatching {
                    conv.sendMessageAsync(next).collect { m ->
                        text.append(textOf(m))
                        if (m.toolCalls.isNotEmpty()) calls = m.toolCalls
                    }
                }.exceptionOrNull()
                if (failure != null) {
                    // A small model writing a tool call slightly wrong is a
                    // typo, not the end of the task: read the call out of the
                    // text it wrote, or ask it to write the call again.
                    if (!isToolCallSyntaxFailure(failure) || rescues++ >= MAX_RESCUES) throw failure
                    val rescued = salvageToolCall(throwableText(failure), schemas)
                    log("step ${step + 1}: tool call unreadable, rescued ${rescued?.name ?: "nothing"}")
                    next = if (rescued == null) {
                        Message.user(WRITE_THE_CALL_AGAIN)
                    } else {
                        // The engine's own turn did not survive the parse, so
                        // the result goes back as plain text rather than as a
                        // reply to a tool call it never recorded.
                        val out = executeTool(tools, bot, group, rescued.name, rescued.arguments, onStage)
                        Message.user("Result of ${rescued.name}:\n$out")
                    }
                    return@repeat
                }
                log("step ${step + 1}: ${text.length} chars, ${calls.size} calls")
                if (text.isNotBlank()) answer = text.toString()
                if (calls.isEmpty()) return@use answer
                val results = calls.map { call ->
                    call.name to executeTool(tools, bot, group, call.name, call.arguments.toJson(), onStage)
                }
                // One step left: hand the results back as words with the
                // wrap-up, so the run ends with its report rather than with
                // another tool call nobody ever sees.
                next = if (step >= MAX_STEPS - 2) {
                    Message.user(
                        results.joinToString("\n\n") { (name, out) -> "Result of $name:\n$out" } + "\n\n" + WRAP_UP,
                    )
                } else {
                    Message.tool(Contents.of(results.map { (name, out) -> Content.ToolResponse(name, out) }))
                }
            }
            answer.ifBlank { OUT_OF_STEPS }
        }
    }

    private suspend fun executeTool(
        tools: LocalTools,
        bot: Bot,
        group: Group?,
        called: String,
        calledArgs: JsonObject,
        stage: (String) -> Unit,
    ): String {
        val name = canonicalTool(called)
        val args = canonicalArgs(name, calledArgs)
        if (name != called) log("tool $called -> $name")
        val looking = name == "browser_open" || name == "browser_read" || name == "web_fetch" || name == "web_search"
        val onComputer = usesComputer(name)
        if (onComputer) {
            browsing.add(bot.id)
            emitComputer(bot.id, true)
        }
        stage(
            when {
                looking -> "Looking on the web"
                name == "open_in_chrome" -> "Opening your browser"
                onComputer -> "Using the computer"
                api.isMcpTool(name) -> "Using your apps"
                else -> "Thinking"
            },
        )
        log("tool $name")
        tools.precheck(name, args)?.let { return it }
        val world = tools.needsApproval(name) || api.isMcpTool(name)
        if (!ask(bot, group, name, tools.describe(name, args), args, touchesWorld = world, tainted = tools.taint.taints(name))) {
            return "The person declined or blocked this step. Do not retry it; ask what they would like instead."
        }
        val out = runCatching {
            if (name == "show_results") {
                showResults(bot, group, args, tools::mayShow)
            } else if (api.isMcpTool(name)) {
                api.callMcp(name, args) { bytes, mime, from -> keepPicture(bot, group, bytes, mime, from) }.take(4000)
            } else {
                tools.run(name, args).take(4000)
            }
        }.getOrElse { "The tool failed: ${it.message}" }
        if (name in RunTaint.SOURCES || api.isMcpTool(name)) tools.taint.mark(name)
        if (name != "show_results") tools.see(out)
        proof?.add(evidenceName(name, args), out)
        // Say the site, not just "working", so the header tells them where
        // the bot is and what they could be watching.
        if (name.startsWith("browser_") && usesComputer(name)) {
            val host = hostOf(browser.url.value)
            if (host.isNotEmpty()) stage("Browsing $host")
        }
        return out
    }

    /** How a step reads to the verifier: what was done, never what was typed. */
    private fun evidenceName(name: String, args: JsonObject): String {
        fun a(k: String) = (args[k] as? JsonPrimitive)?.content.orEmpty()
        return when (name) {
            "browser_open" -> "opened ${a("url")}"
            "browser_read" -> "read the page"
            "browser_click" -> "clicked [${a("id")}]"
            "browser_fill" -> "typed into [${a("id")}]" + if (a("submit") == "true") " and submitted" else ""
            "browser_enter" -> "submitted [${a("id")}]"
            "web_search" -> "searched ${a("query")}"
            "web_fetch" -> "fetched ${a("url")}"
            "file_write", "file_read" -> "$name: ${a("path")}"
            else -> name
        }
    }

    /** A question for the person, on a card; the run waits for the answer. */
    private suspend fun askPerson(bot: Bot, group: Group?, question: String, options: List<String>): String {
        if (question.isBlank()) return "Write the question."
        val id = "c_" + UUID.randomUUID().toString().take(8)
        val card = GMessage(
            id = id, botId = bot.id, groupId = group?.id, role = "card", content = question, createdAt = now(),
            data = buildJsonObject {
                put("kind", "question")
                put("status", "pending")
                put("options", JsonArray(options.map { JsonPrimitive(it) }))
            },
        )
        db.putMessage(card)
        emit("message", bot.id, json.encodeToJsonElement(card), group?.id)
        emitPresence(bot.id, group, "waiting", "Has a question")
        api.awaitAnswer(id)
        emitPresence(bot.id, group, "thinking")
        val m = db.message(id)
        val answer = m?.str("answer").orEmpty()
        return if (m?.str("status") == "answered" && answer.isNotBlank()) "The person answered: $answer"
        else "The person skipped the question. Carry on with your best judgement, or ask it differently."
    }

    /** One plain call to the model, with no tools or history: planning aside, used to check work. */
    private suspend fun oneShot(model: BrainModel?, system: String, prompt: String, maxTokens: Int): String = when (model) {
        is BrainModel.Remote -> api.providers.run(model.provider, model.name, system, emptyList(), prompt, emptyList(), onText = {}, execute = { "" })
        is BrainModel.Local -> withContext(Dispatchers.IO) {
            val config = ConversationConfig(
                systemInstruction = Contents.of(system), initialMessages = emptyList(), tools = emptyList(),
                automaticToolCalling = false, samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2, seed = 0),
                maxOutputToken = maxTokens,
            )
            engine(model.file).createConversation(config).use { conv ->
                val sb = StringBuilder()
                conv.sendMessageAsync(Message.user(prompt.take(6000))).collect { sb.append(textOf(it)) }
                sb.toString()
            }
        }
        null -> ""
    }

    /** A card in the chat, and a wait. Nothing changes outside until it is answered. */
    private suspend fun ask(bot: Bot, group: Group?, tool: String, what: String, args: JsonObject, touchesWorld: Boolean, tainted: Boolean = false): Boolean {
        when (Approval.decide(api.approvalMode(), api.ruleFor(tool), touchesWorld, tool, tainted)) {
            Approval.Choice.Run -> return true
            Approval.Choice.Refuse -> return false
            Approval.Choice.Ask -> Unit
        }

        val id = "c_" + UUID.randomUUID().toString().take(8)
        val card = GMessage(
            id = id, botId = bot.id, groupId = group?.id, role = "card",
            content = what, createdAt = now(),
            data = buildJsonObject {
                put("kind", "approval")
                put("tool", tool)
                put("args", args)
                put("status", "pending")
            },
        )
        db.putMessage(card)
        emit("message", bot.id, json.encodeToJsonElement(card), group?.id)
        emitPresence(bot.id, group, "waiting")
        val ok = api.awaitAnswer(id)
        emitPresence(bot.id, group, "thinking")
        return ok
    }

    /** Providers have no notion of speakers, so teammates are named in the turn. */
    private fun groupHistory(history: List<GMessage>, group: Group?, bot: Bot): List<GMessage> {
        if (group == null) return history
        return history.map { m ->
            if (m.role == "bot" && m.botId != bot.id) {
                m.copy(role = "user", content = "${db.bot(m.botId.orEmpty())?.name ?: "Someone"}: ${m.content}")
            } else m
        }
    }

    private fun historyFor(bot: Bot, group: Group?, prompt: String): List<GMessage> {
        val chat = group?.let { "g:${it.id}" } ?: bot.id
        var list = db.messages(chat).filter { it.role == "user" || it.role == "bot" }
        // In a group the prompt was just stored; don't show it twice.
        if (group != null && list.lastOrNull()?.role == "user" && list.last().content == prompt) list = list.dropLast(1)
        return list.takeLast(if (group == null) 8 else 12)
    }

    private suspend fun engine(file: File): Engine = engineLock.withLock {
        val key = file.absolutePath
        engine?.takeIf { engineKey == key }?.let { return it }
        engine?.let { runCatching { it.close() } }
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.GPU(),
            maxNumTokens = 8192,
            cacheDir = ctx.cacheDir.absolutePath,
        )
        log("loading ${file.name} (GPU)")
        val e = runCatching {
            Engine(config).also { it.initialize() }
        }.getOrElse {
            // Some phones have no usable GPU backend; CPU always works.
            log("GPU load failed; using CPU", it)
            if (!warnedCpu) {
                warnedCpu = true
                runCatching { dev.gbot.app.GbotApp.instance.toast("The GPU couldn't run the model, so this is on the CPU — replies will be slower") }
            }
            Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), maxNumTokens = 8192, cacheDir = ctx.cacheDir.absolutePath))
                .also { it.initialize() }
        }
        engine = e
        engineKey = key
        log("loaded ${file.name}")
        e
    }

    private fun systemPrompt(bot: Bot, group: Group?, peers: List<Bot>): String {
        val profile = db.get("profile").orEmpty().take(700).ifBlank { "Nothing yet." }
        val notes = promptNotes(db.memories(bot.id), 6)
            .joinToString("\n") { "- ${noteDay(it.at)}: ${it.text.take(200)}" }
            .ifBlank { "Nothing yet." }
        val skills = api.localSkills().joinToString("\n") { "- ${it.name}: ${it.description.take(100)}" }.ifBlank { "None yet." }
        return buildString {
            appendLine("You are ${bot.name.ifBlank { "a bot" }}${if (bot.title.isBlank()) "" else ", ${bot.title}"}, running on this person's phone. Today is ${LocalDate.now()}.")
            if (bot.instructions.isNotBlank()) appendLine(bot.instructions.trim())
            appendLine()
            appendLine("How to reply:")
            appendLine("- Answer in the first line. No preamble, no restating the question, no greeting, no sign-off.")
            appendLine("- A short answer in a few sentences or bullets. For a task, as many short bullets as the work needs. Say it once.")
            appendLine("- Shape it with Markdown; the chat renders all of it: short bullets, a small table (2-3 columns) when you compare three or more things, a fenced code block for code, commands or output, `code` for a value or command inline, **bold** on the number or result that matters, a Markdown link for a page you used.")
            appendLine("- A `##` heading only when a long answer has real sections. Never one above a short answer, and never a heading with a single line under it.")
            appendLine("- Report work as one line per step: what you did, the result, what's left for them.")
            appendLine("- If you can answer from this chat, your memory or what you already know, answer now and call no tools.")
            appendLine("- Take the fewest steps that reach the answer. Never re-read a page or re-check a fact you already have, and stop as soon as the answer holds.")
            if (group != null) {
                val others = group.botIds.mapNotNull { db.bot(it)?.name }.filter { it != bot.name }
                appendLine()
                appendLine("You are in the group chat “${group.name}” with ${others.joinToString(", ").ifBlank { "no one else" }}.")
                appendLine("Teammates appear as “Name: …”. Answer as yourself, in a line or two. @Name hands the work to them, in this chat. Don't speak for them.")
            }
            if (peers.isNotEmpty()) {
                appendLine()
                appendLine("The person's other bots, reachable with message_bot: " + peers.joinToString(", ") { it.name } + ".")
                appendLine("Ask (the default) when you need their answer: send every ask you need now, then finish your turn; the answers come back together. Tell when you're only passing something on. Send it yourself instead of asking the person to relay it, and don't announce it — they see it. @Name does nothing outside a group chat.")
            }
            appendLine()
            appendLine("Tools (the schemas say the rest):")
            appendLine("- browser_*: your own browser window, which the person can watch. Use it when the job needs a sign-in, a form or a click — applying, booking, buying, checking their account. browser_read before you act, then use the numbered ids it gives you; one read per action, not two.")
            appendLine("- web_search first for anything current or on the web (one fast step; run several together), then web_fetch on one or two results (or a link the person gave) when a snippet isn't enough: a page's title, main picture and text, nothing on screen. No sign-ins, no clicking. The browser is slower; use it only for sign-ins, forms and pages that need clicks.")
            appendLine("- open_in_chrome when a site needs their own browser; vault_type for a saved secret, never a password you typed.")
            appendLine("- calendar_* and contact_search: if the phone has several accounts the answer lists them; ask the person which with ask_user (give the accounts as options), then call again with account. They are remembered after that.")
            appendLine("- ask_user for any question or choice — a card, not plain text. notify, remember, forget, recall, profile, use_skill, file_*, routine_*.")
            appendLine("- task_contract then task_verify for a multi-step deliverable they will check; a quick answer needs neither. Never claim success against a failed check.")
            appendLine("- show_results for a list of 2+ things to compare (hotels, products, places) after you have read them from a tool: they appear as cards, one per item, with its picture (a page's Image line), price and rating as fields, and its link. Then say what matters in prose; don't repeat every row.")
            if (peers.isNotEmpty()) appendLine("- message_bot: ask or tell another of the person's bots; asks come back together.")
            if (bot.id == MAIN_BOT) appendLine("- bot_list, bot_create, bot_update, bot_archive: the person's other bots. Changes ask first; the main bot can't be archived.")
            if (api.localMcp().any { it.enabled && it.status == "connected" }) appendLine("- Tools starting with mc… come from connected integrations.")
            appendLine()
            appendLine("Rules:")
            appendLine("- Never invent facts about the person; use profile for their details.")
            appendLine("- Never say something was sent, submitted, applied for, booked or bought unless the page confirmed it.")
            appendLine("- remember durable facts and preferences; forget when they correct you.")
            appendLine("- A message may carry an internal plan. Follow it, and never mention it. No plan and the task has several steps? call plan, one step per line.")
            appendLine("- You cannot run shell commands, open other apps, text or post through chat apps.")
            appendLine("- " + LocalTools.mailRule(PhoneApps.on(ctx, "email"), Mail.connected(ctx)))
            appendLine()
            appendLine("The person's details:")
            appendLine(profile)
            appendLine()
            appendLine("What you remember (newest fact wins):")
            append(notes)
            appendLine()
            appendLine()
            appendLine("Skills you can load with use_skill:")
            append(skills)
        }
    }

    /**
     * One bot messaging another, without waiting and without the person in
     * the middle. The same message shows as "Messaged X" in the sender's
     * chat and "Message from Y" in the receiver's, and starts the
     * receiver's run behind this one. An ask's answer lands on both cards
     * and goes back to the sender with the rest of its round; a tell's goes
     * nowhere (docs/protocol/bot-asks.md).
     */
    private fun messageBot(from: Bot, to: Bot, text: String, expect: String, w: Work, round: RunRound, tainted: Boolean): String {
        val body = text.trim()
        if (body.isEmpty()) return "Write something to send."
        val kind = if (expect == "tell") "tell" else "ask"
        if (!book.take(w.root, w.depth)) {
            return "You can't message more bots for this request (at most $MAX_ASKS_PER_ROOT messages, $MAX_ASK_DEPTH bots deep). " +
                "Finish with what you have and tell the person what's missing."
        }
        val roundId = if (kind == "ask") round.id ?: newId("rd_").also { round.id = it; book.open(it) } else null
        val data = exchangeData(
            from.id, to.id, body,
            ask = newId("ak_"), expect = kind, round = roundId, root = w.root, depth = w.depth + 1,
        )
        val out = event(from, null, "Messaged ${to.name}", data)
        val inn = event(to, null, "Message from ${from.name}", data)
        start(
            Work(
                to.id, askPrompt(from.name, body, kind), depth = w.depth + 1, kind = kind, root = w.root,
                exchange = listOf(out.id, inn.id), from = from.id, untrusted = tainted,
            ),
        )
        return if (kind == "tell") {
            "Sent to ${to.name}. Nothing comes back from a tell. The person sees that you sent it, so don't announce it."
        } else {
            "Sent to ${to.name}. Don't wait for the answer: once this turn ends and every bot you asked has answered, " +
                "all the answers come back to you together. The person sees that you sent it, so don't announce it."
        }
    }

    /** Write how a message ended onto its two cards; an ask may finish its round. */
    private fun settle(exchange: List<GMessage>, status: String, reply: String) {
        exchange.forEach { m -> db.message(m.id)?.let { patchCard(it, mapOf("status" to status, "reply" to reply)) } }
        exchange.firstOrNull()?.str("round")?.let(::maybeJoin)
    }

    /**
     * The run that sent a round's asks has ended. The answers go back now
     * if they're all in; otherwise when the last one lands, or when the
     * round runs out of time.
     */
    private fun closeRound(round: String) {
        book.close(round)
        if (!maybeJoin(round)) scope.launch(Dispatchers.IO) {
            delay(ROUND_TIMEOUT_MS)
            expireRound(round)
        }
    }

    /** The sender's card of each message in a round. */
    private fun outbound(round: String): List<GMessage> = db.exchangeCards("round", round).filter { it.botId == it.str("from") }

    /** Hand a round's answers back to the bot that asked, once. True when the round is settled. */
    private fun maybeJoin(round: String): Boolean {
        if (book.isOpen(round)) return false
        if (book.wasJoined(round)) return true
        val outs = outbound(round)
        if (outs.isEmpty() || outs.any { it.str("joined") == "true" }) return true
        if (!roundDone(outs.map { it.str("status") })) return false
        if (!book.claimJoin(round)) return true
        val first = outs.first()
        val sender = first.str("from")?.let { db.bot(it) } ?: return true
        val answers = outs.map {
            Answer(
                it.str("to")?.let { id -> db.bot(id)?.name } ?: "A teammate",
                it.str("message").orEmpty(), it.str("status").orEmpty(), it.str("reply").orEmpty(),
            )
        }
        start(
            Work(
                sender.id, joinPrompt(answers), depth = maxOf((first.str("depth")?.toIntOrNull() ?: 1) - 1, 0),
                kind = "join", root = first.str("root").orEmpty(), joinRound = round,
                // Teammates' answers can carry what they read; their runs'
                // taint isn't recorded on the cards, so treat them as outside text.
                untrusted = true,
            ),
        )
        return true
    }

    /** The answers are going back: say so on the sender's cards, with how long it took. */
    private fun markJoined(round: String) {
        val outs = outbound(round)
        val took = outs.firstOrNull()?.createdAt?.let { runCatching { java.time.Duration.between(java.time.Instant.parse(it), java.time.Instant.now()).toMillis() }.getOrNull() }
        outs.forEach { patchCard(it, buildMap { put("joined", true); if (took != null) put("tookMs", took) }) }
        outs.firstOrNull()?.str("root")?.let { book.traceLine(it) }?.takeIf { it.isNotEmpty() }?.let { log("trace $it") }
    }

    /** Give up on the asks still out after the round timeout, and hand back what came in. */
    private fun expireRound(round: String) {
        db.exchangeCards("round", round).filter { !askEnded(it.str("status")) }.forEach { patchCard(it, mapOf("status" to "expired")) }
        maybeJoin(round)
    }

    /** Merge fields into an exchange card and show the change. */
    private fun patchCard(m: GMessage, fields: Map<String, Any?>) {
        val next = m.copy(data = JsonObject((m.data ?: JsonObject(emptyMap())) + fields.toJson()))
        runCatching { db.patchMessage(next) }.onFailure { log("could not update the card", it) }
        runCatching { emit("message_update", next.botId.orEmpty(), json.encodeToJsonElement(next), next.groupId) }
    }

    /** A quiet line in the transcript: something happened, no reply needed. */
    /**
     * Save a picture a tool handed back and put it in the chat as its own
     * row, then tell the model it is there. A tool only ever answers the
     * model in text, so this is the one way a picture reaches the person.
     */
    private fun keepPicture(bot: Bot, group: Group?, bytes: ByteArray, mime: String, from: String): String {
        val dir = java.io.File(ctx.filesDir, "pictures").apply { mkdirs() }
        val file = java.io.File(dir, "pic_" + UUID.randomUUID().toString().take(8) + extensionOf(mime))
        return try {
            file.writeBytes(bytes)
            event(
                bot, group, "Picture from $from",
                buildJsonObject {
                    put("kind", "picture")
                    put("file", file.absolutePath)
                    put("mime", mime)
                    put("from", from)
                    put("size", bytes.size)
                },
            )
            "[A picture from $from is in the chat where you are answering: ${kindOf(mime)}, ${sizeOf(bytes.size)}. " +
                "The person can see it. Say what it shows; don't describe it as a file or a link.]"
        } catch (e: Throwable) {
            log("could not keep the picture", e)
            "[$from sent a picture gbot couldn't keep.]"
        }
    }

    /**
     * The bot's list to compare, as cards in the chat: the phone's copy of
     * the server's show_results. Returns the line the model reads back.
     */
    private fun showResults(bot: Bot, group: Group?, args: JsonObject, mayShow: (String) -> Boolean): String {
        val set = LocalResults(java.io.File(ctx.filesDir, "pictures"), allowImage = mayShow).build(args)
            ?: return "Give at least two items, each with a title."
        val title = set.title.ifEmpty { "Results" }
        event(
            bot, group, title,
            buildJsonObject {
                put("kind", "results")
                put("title", title)
                put("items", set.items)
            },
        )
        return "Shown ${set.items.size} results as cards in the chat, where you are answering. The person can see them. Summarize what matters; don't list every row again."
    }

    private fun event(bot: Bot, group: Group?, content: String, data: JsonObject? = null): GMessage {
        val m = GMessage(
            id = "m_" + UUID.randomUUID().toString().take(8), botId = bot.id, groupId = group?.id, role = "event",
            content = content, createdAt = now(), data = data,
        )
        runCatching { db.putMessage(m) }.onFailure { log("could not save the event", it) }
        runCatching { emit("message", bot.id, json.encodeToJsonElement(m), group?.id) }
        return m
    }

    private fun report(bot: Bot, group: Group?, e: Throwable) {
        log("run failed", e)
        reply(bot, group, "I couldn't finish: " + why(e))
    }

    /** A network failure in words the person can act on, not the library's. */
    private fun why(e: Throwable): String {
        val raw = e.message?.trim().orEmpty()
        val timedOut = e is java.net.SocketTimeoutException ||
            (e is java.io.InterruptedIOException && raw.contains("timeout", ignoreCase = true)) ||
            raw.equals("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true)
        return when {
            isToolCallSyntaxFailure(e) ->
                "the model on this phone kept writing its tool calls in a way I couldn't read. Ask again in " +
                    "smaller steps, or pick a bigger model in Settings → Models."
            timedOut -> "the model took too long to answer. If it is a big model, give it a smaller task or pick a faster one in Settings → Models."
            e is java.net.UnknownHostException -> "I couldn't reach that endpoint. Check the connection and the address in Settings → Models."
            raw.isNotEmpty() -> raw
            else -> e::class.simpleName.orEmpty().ifEmpty { "something went wrong" }
        }
    }

    /** Save and show an answer, even if the database write fails. */
    private fun reply(bot: Bot, group: Group?, content: String) {
        val m = GMessage(
            id = "m_" + UUID.randomUUID().toString().take(8), botId = bot.id, groupId = group?.id, role = "bot",
            content = content, createdAt = now(),
        )
        runCatching { db.putMessage(m) }.onFailure { log("could not save the reply", it) }
        runCatching {
            emit("message", bot.id, json.encodeToJsonElement(m), group?.id)
            emit("message_done", bot.id, json.encodeToJsonElement(m.copy(streaming = false)), group?.id)
        }.onFailure { log("could not show the reply", it) }
    }

    // What each busy bot is doing. Without this a reload has nothing to
    // report and every bot looks idle, however hard it is working.
    private val presence = java.util.concurrent.ConcurrentHashMap<String, Presence>()

    /** The busy bots, for a fresh bot list. */
    fun presenceSnapshot(): Map<String, Presence> = presence.toMap()

    /**
     * Shows "done" for a moment, then idle — the server's own settle. The
     * phone used to go straight to idle, so a bot that finished while you
     * were elsewhere never said so: this is what notifies.
     */
    private fun settleDone(bot: Bot) {
        emitPresence(bot.id, null, "done")
        scope.launch {
            delay(2500)
            if (presence[bot.id]?.state == "done") emitPresence(bot.id, null, "idle")
        }
    }

    private fun emitPresence(botId: String, group: Group?, state: String, detail: String? = null) {
        val p = Presence(state = state, detail = detail)
        if (state == "idle") presence.remove(botId) else presence[botId] = p
        emit("presence", botId, json.encodeToJsonElement(p), group?.id)
    }

    private fun emitComputer(botId: String, on: Boolean) {
        events.handle(buildJsonObject {
            put("type", "computer")
            put("botId", botId)
            put("data", json.encodeToJsonElement(ComputerStatus(
                state = if (on) "on" else "off",
                controller = "bot",
                network = true,
                available = true,
                backend = "browser",
            )))
        })
    }

    private fun emit(type: String, botId: String, data: JsonElement? = null, groupId: String? = null) {
        events.handle(buildJsonObject {
            put("type", type)
            if (botId.isNotEmpty()) put("botId", botId)
            if (groupId != null) put("groupId", groupId)
            if (data != null) put("data", data)
        })
    }

    private fun Map<String, Any?>.toJson(): JsonObject = JsonObject(mapValues { (_, v) -> jsonOf(v) })

    private fun textOf(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun now(): String = java.time.Instant.now().toString()

    private fun log(msg: String, e: Throwable? = null) {
        if (e == null) Log.i(TAG, msg) else Log.w(TAG, msg, e)
    }

    internal companion object {
        const val TAG = "gbot-local"

        /**
         * A tool argument as JSON, keeping its shape. A provider's arguments
         * are already JSON and pass through as they are; the on-device
         * engine's are plain lists and maps. Turning either into its text
         * would hand a tool "[{…}]" where it needs a list, so show_results
         * would never see its items and every string would gain quotes.
         */
        fun jsonOf(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to jsonOf(x) })
            is Iterable<*> -> JsonArray(v.map { jsonOf(it) })
            is Array<*> -> JsonArray(v.map { jsonOf(it) })
            else -> JsonPrimitive(v.toString())
        }

        /**
         * Model/tool rounds for a model running on the phone itself. A task
         * worth asking for — look, read, compare, write — is many rounds, and
         * stopping at twelve ended most of them with nothing. Tokens are slow
         * on a phone, so the run's own clock, not this, is what usually stops
         * a very long one.
         */
        const val MAX_STEPS = 24

        /**
         * Said on the last round a run has, in place of more tool results.
         * Without it the final round goes on another tool call and the person
         * is left with silence.
         */
        const val WRAP_UP =
            "This is your last step: no more tools. Write your answer now from what you already have — " +
                "what you did, what you found, and anything still left for them."

        /** Said when even the wrap-up step produced no words. */
        const val OUT_OF_STEPS =
            "I ran out of steps before finishing this one. Ask me to carry on and I'll pick up where I stopped."

        /** How many unreadable tool calls one run forgives before it stops. */
        const val MAX_RESCUES = 3

        /** Said to a model whose tool call could not be read at all. */
        const val WRITE_THE_CALL_AGAIN =
            "Your last tool call could not be read. Write it again: one tool, one value per field, " +
                "and several items in a single field as one line each. If no tool is needed, just answer in words."

        /**
         * How long one run may take, matching the server's own bound. Real
         * work — reading pages, filling forms, waiting on an integration —
         * takes minutes, so the clock only stops a run that is truly stuck.
         */
        const val RUN_TIMEOUT_MS = 30 * 60 * 1000L

        /** How long a round waits for its slowest answer; runs wait in line here. */
        const val ROUND_TIMEOUT_MS = 20 * 60 * 1000L

        /** A whole conversation with a provider, tool steps and all. */
        const val PROVIDER_TIMEOUT_MS = 25 * 60 * 1000L
        const val MCP_TIMEOUT_MS = 20_000L

        /** A plan that takes longer than this is not worth blocking the answer. */
        const val PLAN_TIMEOUT_MS = 45_000L
    }
}

/** What a bot replies when it has nothing to send back. */
const val NO_REPLY = "NO_REPLY"

/**
 * Whether a message is worth a planning pass. On a phone every model call
 * waits in the same line, so a short, single-step message is answered
 * straight away: the plan would cost as much as the reply. Anything long,
 * listed, or asking for several things keeps its plan.
 */
fun worthPlanning(prompt: String): Boolean {
    val p = prompt.trim()
    if (p.length >= 180) return true
    if (p.lines().size > 2) return true
    val low = p.lowercase()
    return MULTI_STEP.any { low.contains(it) }
}

private val MULTI_STEP = listOf(
    " and then", " then ", "after that", "first ", "step 1", "1.", "1)", "each of",
    "for every", "compare", "research", "book ", "apply", "order ", "buy ", "fill in", "sign in",
)

/** The plan with blank lines dropped and its length bounded. */
fun cleanPlan(raw: String): String = raw.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .take(6)
    .joinToString("\n")
    .take(700)

/** How the plan reaches the run: in the system prompt, never the chat. */
fun planBlock(plan: String): String = if (plan.isEmpty()) "" else buildString {
    append("\n\nYour plan for this message (internal; never show it or mention it):\n")
    append(plan)
    append("\nFollow it, one step at a time, and use the plan tool if it has to change.")
}

/**
 * A reply with the sentinel taken out, so it never reaches the chat. The
 * marker arrives alone, wrapped in punctuation, or trailing a real answer
 * ("Here's the joke. NO_REPLY"); all three end up as just the answer, or
 * as nothing at all.
 */
fun cleanReply(reply: String): String {
    val out = reply
        .replace(Regex("""(?i)[(\["'`*]*\bNO[ _-]?REPLY\b[)\]"'`*.,:;!]*"""), " ")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .replace(Regex("[ \\t]{2,}"), " ")
        .trim()
    return if (out == "(no answer)") "" else out
}

/**
 * The data for one bot-to-bot message (docs/protocol/bot-asks.md). The chat
 * UI reads exactly these keys to draw the "Messaged X" / "Message from Y"
 * line and the tap-through popup, so a message without them is invisible:
 * no preview, nothing to open.
 */
fun exchangeData(
    from: String,
    to: String,
    message: String,
    ask: String = newId("ak_"),
    expect: String = "ask",
    round: String? = null,
    root: String = "",
    depth: Int = 1,
): JsonObject = buildJsonObject {
    put("kind", "botmsg")
    put("ask", ask)
    put("from", from)
    put("to", to)
    put("message", message)
    put("expect", expect)
    if (round != null) put("round", round)
    if (root.isNotEmpty()) put("root", root)
    put("depth", depth)
    put("status", "sent")
}

/** Runs a person asked for, here or through Slack. */
private val PEOPLE = setOf("person", "routine", "group", "slack")

/** The round a run's asks go into, made when it sends the first one. */
class RunRound(var id: String? = null)

/** One run waiting for, or taking, its turn. */
@kotlinx.serialization.Serializable
data class Work(
    val botId: String,
    val prompt: String,
    val groupId: String? = null,
    val depth: Int = 0,
    /** person | routine | group | ask | tell | join */
    val kind: String = "person",
    /** The person's request this run is part of. */
    val root: String = "",
    /** The two cards (sender's, receiver's) of the message this run answers. */
    val exchange: List<String> = emptyList(),
    /** The bot that sent that message. */
    val from: String? = null,
    /** Set on the run that gets a round's answers back. */
    val joinRound: String? = null,
    /** Set when the message came from Slack: where the answer goes back. */
    val slack: SlackTarget? = null,
    /** Set when the bot that sent this message had read outside text. */
    val untrusted: Boolean = false,
    val tries: Int = 0,
) {
    val priority: Int get() = when (kind) {
        "join" -> Priority.ANSWERS
        "ask", "tell" -> Priority.BOTS
        else -> Priority.PERSON
    }

    /** Saved until it finishes; bots' messages and joins come back from their cards instead. */
    val durable: Boolean get() = kind in PEOPLE

    /** Only a message from the person gets a planning pass. */
    val plans: Boolean get() = kind in PEOPLE
}

sealed class BrainModel {
    data class Local(val file: File) : BrainModel()
    data class Remote(val provider: dev.gbot.app.net.Provider, val name: String) : BrainModel()
}
