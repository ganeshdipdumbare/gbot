package dev.gbot.app.data

import android.content.Context
import dev.gbot.app.net.Api
import dev.gbot.app.net.Bot
import dev.gbot.app.net.ComputerStatus
import dev.gbot.app.net.Group
import dev.gbot.app.net.Message
import dev.gbot.app.net.Model
import dev.gbot.app.net.Presence
import dev.gbot.app.net.QueueItem
import dev.gbot.app.net.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** A chat key is a bot id (its own chat) or "g:<group id>". */
typealias ChatKey = String

fun groupKey(id: String): ChatKey = "g:$id"
fun isGroupKey(k: String?): Boolean = k != null && k.startsWith("g:")
fun keyOf(m: Message): ChatKey = m.groupId?.let(::groupKey) ?: m.botId.orEmpty()

data class State(
    val me: User? = null,
    val bots: List<Bot> = emptyList(),
    val groups: List<Group> = emptyList(),
    val presence: Map<String, Presence> = emptyMap(),
    val computers: Map<String, ComputerStatus> = emptyMap(),
    val messages: Map<ChatKey, List<Message>> = emptyMap(),
    val models: List<Model>? = null,
    val connected: Boolean = false,
    val loaded: Boolean = false,
    val unread: Map<ChatKey, Int> = emptyMap(),
    val hasMore: Map<ChatKey, Boolean> = emptyMap(),
    val queue: Map<ChatKey, List<QueueItem>> = emptyMap(),
    val hidden: Set<String> = emptySet(),
)

private fun lastAt(m: Message?, createdAt: String) = m?.createdAt ?: createdAt
private fun sortBots(b: List<Bot>) = b.sortedWith(compareBy<Bot>({ !it.pinned }).thenByDescending { lastAt(it.lastMessage, it.createdAt) })
private fun sortGroups(g: List<Group>) = g.sortedByDescending { lastAt(it.lastMessage, it.createdAt) }

/**
 * The app's state and every change to it, the same reducers as the web
 * app's store.ts, so both behave alike.
 */
class Store(private var api: Api, context: Context) {
    private val prefs = context.getSharedPreferences("gbot", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State(hidden = prefs.getStringSet("hidden", emptySet())!!.toSet()))
    val state: StateFlow<State> = _state
    val now: State get() = _state.value

    /** The chat on screen right now, if the app is in front. */
    @Volatile var openChat: ChatKey? = null
    @Volatile var foreground: Boolean = false

    fun set(fn: (State) -> State) = _state.update(fn)

    /** A different brain, same screens: the phone or a server. */
    fun use(next: Api) { api = next }

    fun setMe(u: User?) = set { it.copy(me = u) }

    fun reset() = set { State(hidden = it.hidden) }

    fun upsertBot(bot: Bot) = set { s ->
        val old = s.bots.find { it.id == bot.id }
        val merged = bot.copy(lastMessage = bot.lastMessage ?: old?.lastMessage)
        s.copy(bots = sortBots(if (old != null) s.bots.map { if (it.id == bot.id) merged else it } else s.bots + merged))
    }

    fun upsertGroup(g: Group) = set { s ->
        val old = s.groups.find { it.id == g.id }
        val merged = g.copy(lastMessage = g.lastMessage ?: old?.lastMessage)
        s.copy(groups = sortGroups(if (old != null) s.groups.map { if (it.id == g.id) merged else it } else s.groups + merged))
    }

    fun removeBot(id: String) = set { s -> s.copy(bots = s.bots.filter { it.id != id }) }
    fun removeGroup(id: String) = set { s -> s.copy(groups = s.groups.filter { it.id != id }) }

    fun finishMessage(m: Message) {
        patchMessage(keyOf(m), m.id) { it.copy(content = m.content, streaming = false) }
        touchLast(m)
    }

    private fun touchLast(m: Message) {
        if (m.role != "user" && m.role != "bot") return
        set { s ->
            if (m.groupId != null) s.copy(groups = sortGroups(s.groups.map { if (it.id == m.groupId) it.copy(lastMessage = m) else it }))
            else s.copy(bots = sortBots(s.bots.map { if (it.id == m.botId) it.copy(lastMessage = m) else it }))
        }
    }

    fun addMessage(m: Message) {
        val k = keyOf(m)
        set { s ->
            val list = s.messages[k] ?: return@set s
            if (list.any { it.id == m.id }) return@set s
            // Swap an optimistic copy for the real one.
            val i = if (m.role == "user") list.indexOfFirst { it.pending && it.content == m.content } else -1
            val next = if (i >= 0) list.mapIndexed { j, x -> if (j == i) m else x } else list + m
            s.copy(messages = s.messages + (k to next))
        }
        touchLast(m)
        if ((m.role == "bot" || m.role == "card") && (k != openChat || !foreground)) {
            set { s -> s.copy(unread = s.unread + (k to ((s.unread[k] ?: 0) + 1))) }
        }
    }

    fun markRead(k: ChatKey?) {
        if (k != null && now.unread.containsKey(k)) set { s -> s.copy(unread = s.unread - k) }
    }

    fun markUnread(k: ChatKey) = set { s -> s.copy(unread = s.unread + (k to maxOf(1, s.unread[k] ?: 0))) }

    fun patchMessage(k: ChatKey, id: String, fn: (Message) -> Message) = set { s ->
        val list = s.messages[k] ?: return@set s
        s.copy(messages = s.messages + (k to list.map { if (it.id == id) fn(it) else it }))
    }

    fun setPresence(botId: String, p: Presence) {
        presenceAt[botId] = System.currentTimeMillis()
        set { s -> s.copy(presence = s.presence + (botId to p)) }
    }

    fun setComputer(botId: String, c: ComputerStatus) = set { s -> s.copy(computers = s.computers + (botId to c)) }
    fun setQueue(k: ChatKey, items: List<QueueItem>) = set { s -> s.copy(queue = s.queue + (k to items)) }
    fun setConnected(v: Boolean) { if (now.connected != v) set { it.copy(connected = v) } }

    fun setHidden(ids: Set<String>) {
        prefs.edit().putStringSet("hidden", ids).apply()
        set { it.copy(hidden = ids) }
    }

    // When each bot's status last changed through a live event. A bot list
    // fetched before that must not replace it.
    private val presenceAt = mutableMapOf<String, Long>()

    suspend fun loadAll() {
        val since = System.currentTimeMillis()
        val b = api.bots()
        val g = api.groups()
        set { s ->
            val fresh = b.presence.toMutableMap()
            for ((id, at) in presenceAt) {
                if (at < since) continue
                val cur = s.presence[id]
                if (cur != null) fresh[id] = cur else fresh.remove(id)
            }
            s.copy(bots = sortBots(b.bots), groups = sortGroups(g.groups), presence = fresh, loaded = true)
        }
    }

    suspend fun loadModels() {
        val r = runCatching { api.models() }.getOrNull() ?: return
        set { it.copy(models = r.models) }
    }

    suspend fun loadMessages(k: ChatKey) {
        val r = if (isGroupKey(k)) api.groupMessages(k.drop(2)) else api.messages(k)
        set { s ->
            // Keep optimistic messages still sending, and anything that
            // arrived over the live stream while this page was loading.
            val cur = s.messages[k].orEmpty()
            val newest = r.messages.lastOrNull()?.createdAt.orEmpty()
            val live = cur.filter { m ->
                r.messages.none { it.id == m.id } && (m.pending || m.createdAt > newest)
            }
            val merged = (r.messages + live).distinctBy { it.id }.sortedBy { it.createdAt }
            s.copy(messages = s.messages + (k to merged), hasMore = s.hasMore + (k to (r.messages.size >= PAGE)))
        }
    }

    /** Loads the page before the oldest message; returns how many arrived. */
    suspend fun loadOlder(k: ChatKey): Int {
        val list = now.messages[k]
        if (list.isNullOrEmpty() || now.hasMore[k] != true) return 0
        val before = list.first().createdAt
        val r = if (isGroupKey(k)) api.groupMessages(k.drop(2), before) else api.messages(k, before)
        set { s ->
            val cur = s.messages[k].orEmpty()
            val seen = cur.map { it.id }.toSet()
            s.copy(messages = s.messages + (k to (r.messages.filter { it.id !in seen } + cur)), hasMore = s.hasMore + (k to (r.messages.size >= PAGE)))
        }
        return r.messages.size
    }

    suspend fun loadQueue(k: ChatKey) {
        val r = if (isGroupKey(k)) api.groupQueue(k.drop(2)) else api.queue(k)
        setQueue(k, r.items)
    }

    suspend fun queueAction(k: ChatKey, qid: String, action: String) = setQueue(k, api.queueAction(qid, action).items)

    suspend fun clearQueue(k: ChatKey) = setQueue(k, (if (isGroupKey(k)) api.clearGroupQueue(k.drop(2)) else api.clearQueue(k)).items)

    suspend fun retry(k: ChatKey, m: Message) {
        set { s -> s.copy(messages = s.messages + (k to s.messages[k].orEmpty().filter { it.id != m.id })) }
        sendMessage(k, m.content)
    }

    suspend fun sendMessage(k: ChatKey, text: String) {
        val group = if (isGroupKey(k)) k.drop(2) else null
        val tmp = Message(
            id = "tmp-" + UUID.randomUUID(), botId = if (group == null) k else null, groupId = group,
            role = "user", content = text, createdAt = Instant.now().toString(), pending = true,
        )
        set { s -> s.copy(messages = s.messages + (k to (s.messages[k].orEmpty() + tmp))) }
        if (group == null) set { s -> s.copy(presence = s.presence + (k to (s.presence[k] ?: Presence()).copy(state = "thinking"))) }
        touchLast(tmp)
        try {
            val real = if (group != null) api.sendGroup(group, text) else api.send(k, text)
            set { s ->
                val list = s.messages[k].orEmpty()
                val has = list.any { it.id == real.id }
                s.copy(messages = s.messages + (k to (if (has) list.filter { it.id != tmp.id } else list.map { if (it.id == tmp.id) real else it })))
            }
            runCatching { loadQueue(k) }
        } catch (e: Exception) {
            patchMessage(k, tmp.id) {
                it.copy(pending = false, data = buildJsonObject { put("failed", true); put("error", JsonPrimitive(e.message ?: "Couldn't send")) })
            }
            if (group == null) set { s -> s.copy(presence = s.presence + (k to Presence("idle"))) }
        }
    }

    companion object { const val PAGE = 100 }
}
