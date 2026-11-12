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

    // One model runs at a time on a phone; other messages wait their turn in
    // the same queue the chat already draws.
    private val runLock = Mutex()
    private var idleJob: Job? = null
    private val jobs = ConcurrentHashMap<String, Job>()
    private val byQid = ConcurrentHashMap<String, Job>()
    private val queues = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<QueueItem>>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val stopped = ConcurrentHashMap.newKeySet<String>()
    private val browsing = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var warnedCpu = false

    fun launch(bot: Bot, prompt: String) = start(bot, prompt, null, 0)

    fun launchRoutine(bot: Bot, prompt: String) = start(bot, prompt, null, 0)

    fun launchGroup(group: Group, bot: Bot, prompt: String, depth: Int) = start(bot, prompt, group, depth)

    fun queueFor(chat: String): List<QueueItem> = queues[chat].orEmpty().toList()

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

    private fun start(bot: Bot, prompt: String, group: Group?, depth: Int, from: String? = null) {
        // A new message keeps the model warm.
        idleJob?.cancel()
        idleJob = null
        val chat = group?.let { "g:${it.id}" } ?: bot.id
        stopped.remove(chat)
        val qid = "q_" + UUID.randomUUID().toString().take(8)
        queues.getOrPut(chat) { java.util.concurrent.CopyOnWriteArrayList() }.add(QueueItem(qid, prompt.take(120), "queued"))
        emitQueue(chat)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runLock.withLock {
                while (qid in paused && isActive) delay(300)
                doneQueued(chat, qid)
                runCatching { run(bot, prompt, group, depth, from) }
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
            doneQueued(chat, qid)
            scheduleIdleUnload()
        }
        job.start()
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
        if (!runLock.tryLock()) return false
        return try {
            engineLock.withLock { closeEngine() }
            true // already unloaded counts as done
        } finally {
            runLock.unlock()
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
        runLock.withLock { engineLock.withLock { closeEngine() } }
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

    private suspend fun run(bot: Bot, prompt: String, group: Group?, depth: Int, from: String? = null) {
        val model = api.resolveModel(bot.model)
        log("run bot=${bot.name} model=${bot.model.ifBlank { "default" }} group=${group?.id ?: "-"}")
        emitPresence(bot.id, group, "thinking")
        try {
            fun stage(detail: String) = emitPresence(bot.id, group, "working", detail)

            // In a group the teammates are @mentioned instead, and a bot
            // answering a message can't message the sender back: that would
            // start a second exchange rather than answer the first.
            val peers = if (group != null || depth >= MAX_HOPS) emptyList()
            else db.bots().filter { it.id != bot.id && it.id != from }
            val tools = LocalTools(
                ctx, db, browser, bot.id,
                skills = api.localSkills(),
                secret = { name -> api.secret(name) },
                schedule = { name, p, cron -> api.scheduleFromChat(bot.id, name, p, cron) },
                peers = peers,
                messageBot = { target, text -> messageBot(bot, target, text, depth) },
            )
            val mcpTools = withTimeoutOrNull(MCP_TIMEOUT_MS) { api.mcpSchemas() }.orEmpty()
            val history = historyFor(bot, group, prompt)
            val system = systemPrompt(bot, group, peers)

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
                            "${model.provider.name} didn't answer in time. Check the endpoint and key in Settings → Models.",
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
            reply(bot, group, content)
            if (group == null && from != null) passAnswer(bot, from, content, depth)
            if (group != null && depth < 3) handoff(group, bot, content, depth)
        } finally {
            browsing.remove(bot.id)
            emitComputer(bot.id, false)
            emitPresence(bot.id, group, "idle")
        }
    }

    /** Wake teammates this reply @mentions, like the server's group handoff. */
    private fun handoff(group: Group, from: Bot, reply: String, depth: Int) {
        if (!reply.contains("@")) return
        for (id in group.botIds) {
            if (id == from.id) continue
            val mate = db.bot(id) ?: continue
            if (Mentions.matches(reply, mate.name)) {
                launchGroup(group, mate, "Continue the group task. ${from.name} mentioned you: $reply", depth + 1)
            }
        }
    }

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
                val t = m.content.take(2000)
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
            maxOutputToken = 700,
        )

        e.createConversation(config).use { conv ->
            var next: Message = Message.user(prompt.take(4000))
            var answer = ""
            repeat(MAX_STEPS) { step ->
                // The flow emits deltas, so the step's text is their sum.
                onStage("Thinking")
                val text = StringBuilder()
                var calls: List<ToolCall> = emptyList()
                conv.sendMessageAsync(next).collect { m ->
                    text.append(textOf(m))
                    if (m.toolCalls.isNotEmpty()) calls = m.toolCalls
                }
                log("step ${step + 1}: ${text.length} chars, ${calls.size} calls")
                answer = text.toString()
                if (calls.isEmpty()) return@use answer
                val responses = calls.map { call ->
                    Content.ToolResponse(call.name, executeTool(tools, bot, group, call.name, call.arguments.toJson(), onStage))
                }
                next = Message.tool(Contents.of(responses))
            }
            answer
        }
    }

    private suspend fun executeTool(
        tools: LocalTools,
        bot: Bot,
        group: Group?,
        name: String,
        args: JsonObject,
        stage: (String) -> Unit,
    ): String {
        val looking = name == "browser_open" || name == "browser_read" || name == "web_fetch"
        val onComputer = name.startsWith("browser_") || name == "open_in_chrome" || name == "vault_type"
        if (onComputer || looking) {
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
        val world = tools.needsApproval(name) || api.isMcpTool(name)
        if (!ask(bot, group, name, tools.describe(name, args), args, touchesWorld = world)) {
            return "The person declined or blocked this step. Do not retry it; ask what they would like instead."
        }
        return runCatching {
            (if (api.isMcpTool(name)) api.callMcp(name, args) else tools.run(name, args)).take(4000)
        }.getOrElse { "The tool failed: ${it.message}" }
    }

    /** A card in the chat, and a wait. Nothing changes outside until it is answered. */
    private suspend fun ask(bot: Bot, group: Group?, tool: String, what: String, args: JsonObject, touchesWorld: Boolean): Boolean {
        when (Approval.decide(api.approvalMode(), api.ruleFor(tool), touchesWorld)) {
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
        return list.takeLast(if (group == null) 12 else 20)
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
        val profile = db.get("profile").orEmpty().take(1200).ifBlank { "Nothing yet." }
        val notes = db.notes(bot.id).joinToString("\n") { "- " + it.take(500) }.ifBlank { "Nothing yet." }
        val skills = api.localSkills().joinToString("\n") { "- ${it.name}: ${it.description.take(160)}" }.ifBlank { "None yet." }
        return buildString {
            appendLine("You are ${bot.name.ifBlank { "a bot" }}${if (bot.title.isBlank()) "" else ", ${bot.title}"}.")
            if (bot.instructions.isNotBlank()) appendLine(bot.instructions.trim())
            appendLine()
            appendLine("You run entirely on this person's phone. Keep answers short and concrete; you have a small memory.")
            appendLine("Today is ${LocalDate.now()}.")
            if (group != null) {
                val others = group.botIds.mapNotNull { db.bot(it)?.name }.filter { it != bot.name }
                appendLine()
                appendLine("You are in the group chat “${group.name}” with ${others.joinToString(", ").ifBlank { "no one else" }}.")
                appendLine("Messages from teammates appear as “Name: …”. Answer as yourself, briefly. Mention a teammate as @Name to hand the work to them; they answer here, in this chat, not in a private one. Don't speak for them.")
            }
            if (peers.isNotEmpty()) {
                appendLine()
                appendLine("The person's other bots, which you can reach with message_bot: " + peers.joinToString(", ") { it.name } + ".")
                appendLine("It doesn't wait for an answer: the message lands in their own chat and their answer comes back here when they finish. Send it yourself when the task needs another bot — don't ask the person to relay it. The person sees that you sent it, so don't announce it. @Name does nothing outside a group chat — use message_bot.")
            }
            appendLine()
            appendLine("Your tools:")
            appendLine("- browser_open, browser_read, browser_click, browser_fill, browser_enter, browser_back: your own browser window. Always browser_read before you act, and use the numbered element ids it gives you.")
            appendLine("- open_in_chrome: hand the page to the person's real browser when a site needs their sign-in there.")
            appendLine("- vault_type: type a saved secret into a field on a site it was saved for; you never see the value.")
            appendLine("- use_skill: load the full instructions of a skill.")
            appendLine("- notify, remember, recall, web_fetch, profile.")
            if (peers.isNotEmpty()) appendLine("- message_bot: send another of the person's bots a message; their answer comes back to this chat.")
            if (api.localMcp().any { it.enabled && it.status == "ok" }) appendLine("- Tools starting with mc… come from connected integrations; they work like the others.")
            appendLine()
            appendLine("Rules:")
            appendLine("- Do not invent facts about the person. Use profile for forms and personal details.")
            appendLine("- Say what you did in one or two lines. If a step needs their approval, it appears as a card; wait for the answer.")
            appendLine("- You cannot run shell commands, open other apps, text, email, or post through chat apps. To reach another of the person's bots, use message_bot — or @Name inside a group chat.")
            appendLine()
            appendLine("The person's details:")
            appendLine(profile)
            appendLine()
            appendLine("What you remember:")
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
     * chat and "Message from Y" in the receiver's, like a chat, and starts
     * the receiver's run behind this one. A reply is just the next message
     * the other way (see passAnswer).
     */
    private fun messageBot(from: Bot, to: Bot, text: String, depth: Int): String {
        val body = text.trim()
        if (body.isEmpty()) return "Write something to send."
        deliver(from, to, body, depth)
        return "Sent to ${to.name}. Their answer comes back here, so don't wait for it in this reply."
    }

    /** Show one bot-to-bot message in both chats, then wake the receiver. */
    private fun deliver(from: Bot, to: Bot, body: String, depth: Int) {
        val data = exchangeData(from.id, to.id, body)
        event(from, null, "Messaged ${to.name}", data)
        event(to, null, "Message from ${from.name}", data)
        start(
            to,
            "Message from ${from.name}, another of the person's bots. Answer ${from.name} directly: " +
                "your reply is sent back to them. If nothing needs to go back, reply with exactly $NO_REPLY.\n\n$body",
            null, depth + 1, from = from.id,
        )
    }

    /**
     * A bot answered a message from another bot: that answer is a new
     * message the other way, shown in both chats. The chain stops after
     * MAX_HOPS.
     */
    private fun passAnswer(bot: Bot, fromId: String, answer: String, depth: Int) {
        if (!carriesAnswer(answer) || depth >= MAX_HOPS) return
        val sender = db.bot(fromId) ?: return
        deliver(bot, sender, answer.trim(), depth)
    }

    /** A quiet line in the transcript: something happened, no reply needed. */
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
        reply(bot, group, "I couldn't finish: ${e.message ?: e::class.simpleName}")
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

    private fun Map<String, Any?>.toJson(): JsonObject = JsonObject(mapValues { (_, v) ->
        when (v) {
            null -> JsonNull
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })

    private fun textOf(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun now(): String = java.time.Instant.now().toString()

    private fun log(msg: String, e: Throwable? = null) {
        if (e == null) Log.i(TAG, msg) else Log.w(TAG, msg, e)
    }

    private companion object {
        const val TAG = "gbot-local"
        const val MAX_STEPS = 8
        const val RUN_TIMEOUT_MS = 5 * 60 * 1000L

        /** How far a chain of bots messaging each other may go. */
        const val MAX_HOPS = 3
        const val PROVIDER_TIMEOUT_MS = 120_000L
        const val MCP_TIMEOUT_MS = 8_000L
    }
}

/** What a bot replies when it has nothing to send back. */
const val NO_REPLY = "NO_REPLY"

/**
 * The data for one bot-to-bot message. The chat UI reads exactly these keys
 * to draw the "Messaged X" / "Message from Y" line and the tap-through
 * popup, so a message without them is invisible: no preview, nothing to
 * open.
 */
fun exchangeData(from: String, to: String, message: String): JsonObject = buildJsonObject {
    put("kind", "botmsg")
    put("from", from)
    put("to", to)
    put("message", message)
    put("status", "sent")
}

/** Whether a bot's reply is worth carrying back to the bot that wrote first. */
fun carriesAnswer(reply: String): Boolean {
    val body = reply.trim()
    return body.isNotEmpty() && body != "(no answer)" && !body.trim(' ', '.', '`', '*').equals(NO_REPLY, ignoreCase = true)
}

sealed class BrainModel {
    data class Local(val file: File) : BrainModel()
    data class Remote(val provider: dev.gbot.app.net.Provider, val name: String) : BrainModel()
}
