package dev.gbot.app.local

import android.content.Context
import dev.gbot.app.live.Events
import dev.gbot.app.net.Api
import dev.gbot.app.net.SlackInstall
import dev.gbot.app.net.SlackLink
import dev.gbot.app.net.SlackResp
import dev.gbot.app.net.Bot
import dev.gbot.app.net.BotsResp
import dev.gbot.app.net.CodexLogin
import dev.gbot.app.net.ComputerStatus
import dev.gbot.app.net.DiscoverResp
import dev.gbot.app.net.Group
import dev.gbot.app.net.GroupsResp
import dev.gbot.app.net.Look
import dev.gbot.app.net.McpResp
import dev.gbot.app.net.MessagesResp
import dev.gbot.app.net.McpServer
import dev.gbot.app.net.Member
import dev.gbot.app.net.MembersResp
import dev.gbot.app.net.Message
import dev.gbot.app.net.Model
import dev.gbot.app.net.ModelsResp
import dev.gbot.app.net.Provider
import dev.gbot.app.net.ProvidersResp
import dev.gbot.app.net.QueueItem
import dev.gbot.app.net.QueueResp
import dev.gbot.app.net.Routine
import dev.gbot.app.net.RulesResp
import dev.gbot.app.net.RoutinesResp
import dev.gbot.app.net.Setup
import dev.gbot.app.net.Sharing
import dev.gbot.app.net.Skill
import dev.gbot.app.net.SkillsResp
import dev.gbot.app.net.Space
import dev.gbot.app.net.SpaceIdResp
import dev.gbot.app.net.SpacesResp
import dev.gbot.app.net.TextResp
import dev.gbot.app.net.ToolRule
import dev.gbot.app.net.UrlResp
import dev.gbot.app.net.User
import dev.gbot.app.net.VaultResp
import dev.gbot.app.net.VaultSecret
import dev.gbot.app.net.VoiceInfo
import dev.gbot.app.net.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Api the app already knows, answered from the phone instead of a
 * server. Everything a server would keep (roster, chat, memory, providers)
 * lives in [LocalDb]; what only a server can do is stubbed out.
 */
class LocalApi(
    private val ctx: Context,
    val db: LocalDb,
    private val events: Events,
    private val scope: CoroutineScope,
) : Api {

    val providers = LocalProviders(ctx, db)
    val browser = BrowserController(ctx)
    val mcp = Mcp(ctx)
    val brain by lazy { LocalBrain(ctx, db, this, events, browser, scope) }
    private val prefs = ctx.getSharedPreferences("gbot", android.content.Context.MODE_PRIVATE)
    private val routines by lazy {
        LocalRoutines(db, scope) { r -> db.bot(r.botId)?.let { brain.launchRoutine(it, r.prompt) } }
    }

    private val cards = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val autoTried = ConcurrentHashMap.newKeySet<String>()

    // Account

    override suspend fun setup(): Setup = Setup()

    override suspend fun signup(email: String, password: String, name: String): User = me()

    override suspend fun login(email: String, password: String): User = me()

    override suspend fun logout() = Unit

    override suspend fun me(): User {
        val saved = db.get("me")
        if (saved != null) runCatching { return json.decodeFromString(User.serializer(), saved) }
        return User(id = "local", name = "You", email = "", defaultModel = defaultModelId(), approvalMode = approvalMode())
    }

    override suspend fun updateMe(defaultModel: String?, approvalMode: String?): User {
        defaultModel?.let { db.set("defaultModel", it) }
        approvalMode?.let { db.set("approvalMode", it) }
        val u = me().copy(
            defaultModel = defaultModel ?: me().defaultModel,
            approvalMode = approvalMode ?: me().approvalMode,
        )
        db.set("me", json.encodeToString(User.serializer(), u))
        return u
    }

    // Models

    override suspend fun models(): ModelsResp = withContext(Dispatchers.IO) {
        val local = LocalModels.files(ctx).map {
            Model(id = LocalModels.id(it), label = it.nameWithoutExtension, provider = "This phone", local = true, tools = true)
        }
        val remote = providers.models()
        val all = (local + remote).map { it.copy(best = false) }
        val best = all.firstOrNull { it.local } ?: all.firstOrNull()
        ModelsResp(models = all.map { it.copy(best = it.id == best?.id) }, ollamaHost = "")
    }

    fun defaultModelId(): String = db.get("defaultModel").orEmpty().ifBlank {
        LocalModels.files(ctx).firstOrNull()?.let { LocalModels.id(it) }
            ?: providers.models().firstOrNull()?.id.orEmpty()
    }

    fun resolveModel(wanted: String): BrainModel? {
        val explicit = wanted.ifBlank { db.get("defaultModel").orEmpty() }
        modelFor(explicit)?.let { return it }
        // The chosen model may be gone (deleted file, removed provider).
        // Fall back the same way the server does, without looping.
        val first = LocalModels.files(ctx).firstOrNull()?.let { LocalModels.id(it) }
            ?: providers.models().firstOrNull()?.id
        return if (first != null && first != explicit) modelFor(first) else null
    }

    private fun modelFor(id: String): BrainModel? {
        if (id.isBlank()) return null
        if (id.startsWith("local:")) return LocalModels.find(ctx, id)?.let { BrainModel.Local(it) }
        val prefix = id.substringBefore(':', "")
        val name = id.substringAfter(':', "")
        val p = providers.get(prefix) ?: return null
        return if (name.isNotBlank()) BrainModel.Remote(p, name) else null
    }

    // Bots

    /**
     * The person's main bot: first in the roster, the one the logo opens,
     * and the only one that may hatch, change or archive the others.
     */
    override suspend fun bots(): BotsResp = withContext(Dispatchers.IO) {
        val all = db.bots().let { list -> if (list.any { it.id == MAIN_BOT }) list else list + hatchMainBot() }
        BotsResp(
            bots = all.sortedBy { if (it.id == MAIN_BOT) 0 else 1 }.map { it.copy(lastMessage = db.lastMessage(it.id)) },
            presence = brain.presenceSnapshot(),
        )
    }

    private fun hatchMainBot(): Bot {
        val b = Bot(
            id = MAIN_BOT, name = "gbot", title = "Your main bot",
            instructions = MAIN_PURPOSE, model = defaultModelId(), pinned = true,
            createdAt = Instant.now().toString(),
        )
        db.putBot(b)
        return b
    }

    override suspend fun createBot(name: String, title: String, look: Look): Bot = withContext(Dispatchers.IO) {
        val b = Bot(
            id = "b_" + UUID.randomUUID().toString().take(8),
            name = name, title = title, look = look,
            model = defaultModelId(),
            createdAt = Instant.now().toString(),
        )
        db.putBot(b)
        emit("bot", b.id, json.encodeToJsonElement(Bot.serializer(), b))
        b
    }

    override suspend fun updateBot(id: String, b: JsonObject): Bot = withContext(Dispatchers.IO) {
        val old = db.bot(id) ?: throw IllegalStateException("That bot is gone")
        val next = old.copy(
            name = b.str("name").ifBlank { old.name },
            title = b.str("title").ifBlank { old.title },
            instructions = if (b.containsKey("instructions")) b.str("instructions") else old.instructions,
            model = if (b.containsKey("model")) b.str("model") else old.model,
            pinned = if (b.containsKey("pinned")) b.flag("pinned") else old.pinned,
            readAloud = if (b.containsKey("readAloud")) b.flag("readAloud") else old.readAloud,
            voice = if (b.containsKey("voice")) b.str("voice") else old.voice,
            look = (b["look"] as? JsonObject)?.let { l ->
                old.look.copy(
                    shape = l["shape"]?.jsonPrimitive?.contentOrNull ?: old.look.shape,
                    hue = l["hue"]?.jsonPrimitive?.contentOrNull ?: old.look.hue,
                    eyes = l["eyes"]?.jsonPrimitive?.contentOrNull ?: old.look.eyes,
                    seed = l["seed"]?.jsonPrimitive?.contentOrNull ?: old.look.seed,
                )
            } ?: old.look,
        )
        db.putBot(next)
        emit("bot", id, json.encodeToJsonElement(Bot.serializer(), next))
        next
    }

    override suspend fun archiveBot(id: String) = withContext(Dispatchers.IO) {
        check(id != MAIN_BOT) { "The main bot can't be archived." }
        db.removeBot(id)
        emit("bot_removed", id)
    }

    // Chat

    override suspend fun messages(id: String, before: String?): MessagesResp =
        withContext(Dispatchers.IO) { MessagesResp(db.messages(id, before)) }

    override suspend fun send(id: String, text: String): Message = withContext(Dispatchers.IO) {
        val bot = db.bot(id) ?: throw IllegalStateException("That bot is gone")
        val m = Message(
            id = "m_" + UUID.randomUUID().toString().take(8), botId = id, role = "user",
            content = text, createdAt = Instant.now().toString(),
        )
        db.putMessage(m)
        brain.launch(bot, text)
        m
    }

    override suspend fun stop(id: String) = brain.cancel(id)

    override suspend fun queue(id: String): QueueResp = QueueResp(brain.queueFor(id))

    override suspend fun queueAction(qid: String, action: String): QueueResp {
        val chat = brain.chatOf(qid)
        brain.queueAction(qid, action)
        return QueueResp(chat?.let { brain.queueFor(it) }.orEmpty())
    }

    override suspend fun clearQueue(id: String): QueueResp {
        brain.clearQueue(id)
        return QueueResp()
    }

    override suspend fun answer(cardId: String, decision: String, text: String?, always: Boolean?): Message = withContext(Dispatchers.IO) {
        val m = db.message(cardId) ?: throw IllegalStateException("That card is gone")
        val approved = decision == "approve" || decision == "accept" || decision == "yes"
        val status = when {
            decision == "approve" || decision == "accept" || decision == "yes" -> "approved"
            decision == "reject" || decision == "decline" || decision == "no" -> "denied"
            else -> "answered"
        }
        val next = m.copy(data = (m.data ?: JsonObject(emptyMap())).toMutableMap().let { d ->
            d["status"] = JsonPrimitive(status)
            if (text != null) d["answer"] = JsonPrimitive(text)
            JsonObject(d)
        })
        db.patchMessage(next)
        if (approved && always == true) m.str("tool")?.takeIf { it.isNotBlank() }?.let { setRule(it, "allow") }
        cards.remove(cardId)?.complete(approved)
        emit("message_update", m.botId.orEmpty(), json.encodeToJsonElement(Message.serializer(), next))
        next
    }

    suspend fun awaitAnswer(cardId: String): Boolean {
        // A very fast tap can land before the wait starts.
        db.message(cardId)?.str("status")?.takeIf { it != "pending" }?.let { return it == "approved" }
        val d = cards.getOrPut(cardId) { CompletableDeferred() }
        return runCatching { d.await() }.getOrDefault(false)
    }

    // Settings

    fun approvalMode(): String = db.get("approvalMode") ?: "auto"

    fun ruleList(): List<ToolRule> = db.get("rules")?.let {
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ToolRule.serializer()), it) }.getOrNull()
    }.orEmpty()

    fun ruleFor(tool: String): String? = ruleList().lastOrNull { it.tool == tool }?.rule

    override suspend fun rules(): RulesResp = RulesResp(ruleList())

    override suspend fun setRule(tool: String, rule: String): RulesResp {
        val list = ruleList().filter { it.tool != tool }.toMutableList()
        if (rule.isNotBlank()) list += ToolRule(tool, rule)
        db.set("rules", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ToolRule.serializer()), list))
        return RulesResp(list)
    }

    // Providers

    override suspend fun providers(): ProvidersResp = withContext(Dispatchers.IO) {
        // Providers saved before discovery existed (or with it skipped) come
        // back empty. Ask each one once per app run so a connected provider
        // is immediately usable, without waiting for a Refresh tap.
        providers.list()
            .filter { it.enabled && it.models.isEmpty() && it.status != "connecting" && autoTried.add(it.id) }
            .forEach { p ->
                db.putProvider(p.copy(status = "connecting"))
                scope.launch { db.putProvider(providers.refreshed(p)) }
            }
        ProvidersResp(providers = providers.list(), codex = CodexLogin())
    }

    override suspend fun createProvider(b: JsonObject): Provider = providers.create(b)

    override suspend fun updateProvider(id: String, b: JsonObject): Provider {
        val p = providers.get(id) ?: throw IllegalStateException("That provider is gone")
        return providers.update(p, b)
    }

    override suspend fun deleteProvider(id: String) = providers.delete(id)

    override suspend fun refreshProvider(id: String): Provider {
        val p = providers.get(id) ?: throw IllegalStateException("That provider is gone")
        val found = runCatching { providers.discover(p.kind, p.baseUrl, "", p.id) }
        val next = p.copy(models = found.getOrElse { p.models }, status = if (found.isSuccess) "connected" else "error")
        db.putProvider(next)
        return next.copy(hasKey = providers.key(id)?.isNotEmpty() == true)
    }


    override suspend fun discoverProvider(b: JsonObject): DiscoverResp = DiscoverResp(
        models = providers.discover(b.str("kind"), b.str("baseUrl"), b.str("apiKey"), b.str("id").ifBlank { null }),
    )

    // Things that only a server has

    override suspend fun computer(id: String): ComputerStatus = ComputerStatus(
        state = if (brain.isBrowsing(id)) "on" else "off",
        controller = "bot",
        network = true,
        available = true,
        backend = "browser",
    )

    override suspend fun computerAction(id: String, action: String, body: JsonObject): ComputerStatus = computer(id)

    override fun screenPath(id: String) = ""

    override fun streamPath(id: String) = ""

    // Groups

    override suspend fun groups(): GroupsResp = withContext(Dispatchers.IO) {
        GroupsResp(groups = db.groups().map { it.copy(lastMessage = db.lastMessage("g:${it.id}")) })
    }

    override suspend fun createGroup(name: String, botIds: List<String>): Group = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty() || botIds.size !in 2..6 || botIds.distinct().size != botIds.size) {
            throw IllegalArgumentException("Name the group and pick 2 to 6 bots")
        }
        if (botIds.any { db.bot(it) == null }) throw IllegalArgumentException("You can only put bots on this phone in a group")
        val g = Group(
            id = "gr_" + UUID.randomUUID().toString().take(8), name = clean, botIds = botIds,
            createdAt = Instant.now().toString(),
        )
        db.putGroup(g)
        emit("group", g.id, json.encodeToJsonElement(Group.serializer(), g), groupId = g.id)
        g
    }

    override suspend fun archiveGroup(id: String) = withContext(Dispatchers.IO) {
        brain.cancelGroup(id)
        db.removeGroup(id)
        emit("group_removed", "", groupId = id)
    }

    override suspend fun groupMessages(id: String, before: String?): MessagesResp =
        withContext(Dispatchers.IO) { MessagesResp(db.messages("g:$id", before)) }

    override suspend fun sendGroup(id: String, text: String): Message = withContext(Dispatchers.IO) {
        val g = db.group(id) ?: throw IllegalStateException("That group is gone")
        val m = Message(
            id = "m_" + UUID.randomUUID().toString().take(8), groupId = id, role = "user",
            content = text, createdAt = Instant.now().toString(),
        )
        db.putMessage(m)
        val members = g.botIds.mapNotNull { db.bot(it) }
        val targets = members.filter { Mentions.matches(text, it.name) }.ifEmpty { members.take(1) }
        targets.forEach { brain.launchGroup(g, it, text, 0) }
        m
    }

    override suspend fun stopGroup(id: String) = brain.cancelGroup(id)

    override suspend fun groupQueue(id: String): QueueResp = QueueResp(brain.queueFor("g:$id"))

    override suspend fun clearGroupQueue(id: String): QueueResp {
        brain.clearQueue("g:$id")
        return QueueResp()
    }

    override suspend fun voice(): VoiceInfo = VoiceInfo(transcribe = false, speak = false)

    override suspend fun transcribe(audio: ByteArray, mime: String): String = unsupported()

    override suspend fun speak(text: String, voice: String?): ByteArray = unsupported()

    override suspend fun sharing(id: String): Sharing = Sharing()

    override suspend fun setVisibility(id: String, v: String): Sharing = unsupported()

    override suspend fun addBotMember(id: String, email: String, role: String): Sharing = unsupported()

    override suspend fun removeBotMember(id: String, userId: String): Sharing = unsupported()

    // Slack needs a server Slack can reach; on the phone there is none.
    override suspend fun slack(): SlackResp = SlackResp()
    override suspend fun connectSlack(b: JsonObject): SlackInstall = throw UnsupportedOperationException("Slack needs your server")
    override suspend fun disconnectSlack(id: String) {}
    override suspend fun linkSlack(id: String, b: JsonObject): SlackLink = throw UnsupportedOperationException("Slack needs your server")
    override suspend fun unlinkSlack(id: String, channelId: String) {}

    override suspend fun spaces(): SpacesResp = SpacesResp()

    override suspend fun createSpace(name: String): Space = unsupported()

    override suspend fun selectSpace(id: String): SpaceIdResp = unsupported()

    override suspend fun spaceMembers(id: String): MembersResp = MembersResp()

    override suspend fun addSpaceMember(id: String, email: String): MembersResp = unsupported()

    override suspend fun removeSpaceMember(id: String, userId: String): MembersResp = unsupported()

    // Routines

    override suspend fun routines(id: String): RoutinesResp = withContext(Dispatchers.IO) { RoutinesResp(db.routines(id)) }

    override suspend fun createRoutine(id: String, name: String, prompt: String, kind: String, schedule: String?): Routine =
        withContext(Dispatchers.IO) {
            if (name.isBlank() || prompt.isBlank()) throw IllegalArgumentException("Give the routine a name and an instruction")
            if (kind != "schedule") throw IllegalArgumentException("Webhooks need your gbot server. On this phone use a schedule.")
            val cron = Cron.parse(schedule.orEmpty()) ?: throw IllegalArgumentException("That isn't a schedule. Use 5 fields, like 0 9 * * *")
            val now = ZonedDateTime.now()
            val r = Routine(
                id = "r_" + UUID.randomUUID().toString().take(8), botId = id, name = name.trim(), prompt = prompt.trim(),
                kind = "schedule", schedule = schedule?.trim(), `when` = Cron.describe(schedule.orEmpty()), active = true,
                nextRunAt = cron.next(now)?.toInstant()?.toString(), lastRunAt = null,
            )
            db.putRoutine(r)
            r
        }

    override suspend fun updateRoutine(id: String, rid: String, active: Boolean): Routine = withContext(Dispatchers.IO) {
        val r = db.routines(id).find { it.id == rid } ?: throw IllegalStateException("That routine is gone")
        val next = if (active) {
            Cron.parse(r.schedule.orEmpty())?.next(ZonedDateTime.now())?.toInstant()?.toString() ?: r.nextRunAt
        } else r.nextRunAt
        val out = r.copy(active = active, nextRunAt = next)
        db.putRoutine(out)
        out
    }

    override suspend fun deleteRoutine(id: String, rid: String) = withContext(Dispatchers.IO) { db.removeRoutine(rid) }

    /** The routine_create tool: the same routine the settings screen makes. */
    suspend fun scheduleFromChat(botId: String, name: String, prompt: String, cron: String): String {
        val r = createRoutine(botId, name, prompt, "schedule", cron)
        val next = r.nextRunAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        return "Scheduled “${r.name}” (${r.schedule}). ${next?.let { "Next run ${it.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}." } ?: ""}"
    }

    override suspend fun runRoutine(id: String, rid: String) = withContext(Dispatchers.IO) {
        val r = db.routines(id).find { it.id == rid } ?: throw IllegalStateException("That routine is gone")
        db.bot(id)?.let { brain.launchRoutine(it, r.prompt) }
        Unit
    }

    // Integrations

    override suspend fun mcp(): McpResp = withContext(Dispatchers.IO) { McpResp(db.mcp()) }

    override suspend fun startMcpOAuth(name: String, url: String): UrlResp =
        throw UnsupportedOperationException("Sign-in integrations need your gbot server. Add this one with a token instead.")

    override suspend fun addMcp(name: String, url: String, token: String?): McpServer = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            throw IllegalArgumentException("The integration needs an http(s) address")
        }
        val m = McpServer(
            id = UUID.randomUUID().toString().take(6), name = name.trim().ifBlank { "Integration" },
            url = clean, hasAuth = !token.isNullOrBlank(), enabled = true, status = "connecting",
        )
        mcp.putToken(m.id, token)
        db.putMcp(m)
        forgetMcp()
        scope.launch {
            val probed = mcp.probe(m)
            db.putMcp(probed)
            forgetMcp()
            if (probed.status == "ok") toast("${probed.name} connected")
        }
        m
    }

    override suspend fun toggleMcp(id: String, enabled: Boolean): McpServer = withContext(Dispatchers.IO) {
        val m = db.mcp().find { it.id == id } ?: throw IllegalStateException("That integration is gone")
        val next = m.copy(enabled = enabled, status = if (enabled) "connecting" else m.status)
        db.putMcp(next)
        forgetMcp()
        if (enabled) scope.launch { db.putMcp(mcp.probe(next)); forgetMcp() }
        next
    }

    override suspend fun deleteMcp(id: String) = withContext(Dispatchers.IO) {
        mcp.putToken(id, null)
        db.removeMcp(id)
        forgetMcp()
    }

    // Vault

    private fun vaultValue(id: String): String = prefs.getString("vault_$id", "").orEmpty()

    override suspend fun vault(): VaultResp = withContext(Dispatchers.IO) { VaultResp(db.vault()) }

    override suspend fun addVault(name: String, value: String, origins: List<String>): VaultSecret = withContext(Dispatchers.IO) {
        val id = "v_" + UUID.randomUUID().toString().take(8)
        val v = VaultSecret(id = id, name = name.trim(), origins = origins, updatedAt = Instant.now().toString())
        prefs.edit().putString("vault_$id", value).apply()
        db.putVault(v)
        v
    }

    override suspend fun updateVault(id: String, name: String, origins: List<String>, value: String?): VaultSecret =
        withContext(Dispatchers.IO) {
            val old = db.vault().find { it.id == id } ?: throw IllegalStateException("That secret is gone")
            if (!value.isNullOrEmpty()) prefs.edit().putString("vault_$id", value).apply()
            val v = old.copy(name = name.trim(), origins = origins, updatedAt = Instant.now().toString())
            db.putVault(v)
            v
        }

    override suspend fun deleteVault(id: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove("vault_$id").apply()
        db.removeVault(id)
    }

    /** For the vault_type tool: the secret and its value, never shown to the model. */
    fun secret(name: String): Pair<VaultSecret, String>? {
        val v = db.vault().find { it.name.equals(name.trim(), ignoreCase = true) } ?: return null
        val value = vaultValue(v.id)
        return if (value.isEmpty()) null else v to value
    }

    // Skills

    override suspend fun skills(): SkillsResp = withContext(Dispatchers.IO) { SkillsResp(db.skills()) }

    override suspend fun saveSkill(name: String, description: String, body: String): Skill = withContext(Dispatchers.IO) {
        if (name.isBlank()) throw IllegalArgumentException("Give the skill a name")
        val existing = db.skills().find { it.name.equals(name.trim(), ignoreCase = true) }
        val s = Skill(
            id = existing?.id ?: ("sk_" + UUID.randomUUID().toString().take(8)),
            name = name.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-'),
            description = description.trim(), body = body,
        )
        db.putSkill(s)
        s
    }

    override suspend fun deleteSkill(id: String) = withContext(Dispatchers.IO) { db.removeSkill(id) }

    fun localSkills(): List<Skill> = db.skills()
    fun localMcp(): List<McpServer> = db.mcp()

    @Volatile private var mcpCache: Pair<Long, List<ToolSchema>>? = null

    suspend fun mcpSchemas(): List<ToolSchema> {
        val cached = mcpCache
        if (cached != null && System.currentTimeMillis() - cached.first < 5 * 60_000) return cached.second
        val list = mcp.schemas(localMcp())
        mcpCache = System.currentTimeMillis() to list
        return list
    }

    private fun forgetMcp() { mcpCache = null }

    fun isMcpTool(name: String): Boolean = localMcp().any { it.enabled && name.startsWith(mcp.prefix(it.id)) }

    suspend fun callMcp(name: String, args: JsonObject): String {
        val s = localMcp().firstOrNull { name.startsWith(mcp.prefix(it.id)) }
            ?: throw IllegalStateException("That integration is disconnected")
        return mcp.call(s, name.removePrefix(mcp.prefix(s.id)), args)
    }

    private fun <T> unsupported(): T = throw UnsupportedOperationException("That needs your gbot server")

    fun start() = routines.start()

    fun stopLocal() {
        routines.stop()
        runCatching { browser.destroy() }
        scope.launch { brain.shutdown() }
    }

    fun loadedModelId(): String? = runCatching { brain.loadedModelId() }.getOrNull()

    fun modelIdleMinutes(): Int = runCatching { brain.idleMinutes() }.getOrDefault(2)

    fun setModelIdleMinutes(minutes: Int) = runCatching { brain.setIdleMinutes(minutes) }

    suspend fun unloadModel(): Boolean = runCatching { brain.unloadNow() }.getOrDefault(false)

    /** Background or low memory: let go of the model if nothing is running. */
    fun trimIfIdle() {
        scope.launch { runCatching { brain.unloadIfIdle() } }
    }

    private fun toast(text: String) = dev.gbot.app.GbotApp.instance.toast(text)

    private fun emit(type: String, botId: String, data: kotlinx.serialization.json.JsonElement? = null, groupId: String? = null) {
        events.handle(buildJsonObject {
            put("type", type)
            if (botId.isNotEmpty()) put("botId", botId)
            if (groupId != null) put("groupId", groupId)
            if (data != null) put("data", data)
        })
    }
}

private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.flag(k: String): Boolean = this[k]?.jsonPrimitive?.contentOrNull == "true"

/** The person's main bot: first in the roster, and what the logo opens. */
const val MAIN_BOT = "b_gbot"

/** What the main bot is for. Its powers are the tools, not these words. */
const val MAIN_PURPOSE =
    "You are the person's main bot. You look after their other bots: on request, or when a task " +
        "clearly needs one, you can list them (bot_list), hatch a new one (bot_create), change a " +
        "title, instructions or model (bot_update), or archive one (bot_archive). Every change " +
        "shows the person an approval card first, so ask for nothing extra."
