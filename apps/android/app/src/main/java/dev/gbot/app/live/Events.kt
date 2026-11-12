package dev.gbot.app.live

import dev.gbot.app.data.Store
import dev.gbot.app.data.groupKey
import dev.gbot.app.data.keyOf
import dev.gbot.app.net.Bot
import dev.gbot.app.net.ComputerStatus
import dev.gbot.app.net.Group
import dev.gbot.app.net.Message
import dev.gbot.app.net.Presence
import dev.gbot.app.net.QueueItem
import dev.gbot.app.net.json
import dev.gbot.app.net.s
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Applies one event to the store. The server's live stream sends these, and
 * so does the on-device brain, so both render exactly alike.
 */
class Events(private val store: Store, private val alerts: Alerts) {
    private inline fun <reified T> JsonObject.data(): T? = this["data"]?.let { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

    fun handle(e: JsonObject) {
        val botId = e.s("botId")
        val groupId = e.s("groupId")
        when (e.s("type")) {
            "presence" -> {
                val p = e.data<Presence>() ?: return
                val id = botId ?: return
                val prev = store.now.presence[id]?.state
                store.setPresence(id, p)
                val b = store.now.bots.find { it.id == id } ?: return
                if (p.state == "waiting" && prev != "waiting") alerts.needsYou(b, p.detail)
                if (p.state == "done" && prev != null && prev != "done") alerts.done(b)
            }
            "message" -> {
                val m = e.data<Message>() ?: return
                if (m.role == "card") alerts.card(m)
                store.addMessage(m.copy(streaming = m.role == "bot"))
            }
            "delta" -> {
                val d = e["data"]?.jsonObject ?: return
                val k = groupId?.let(::groupKey) ?: botId ?: return
                val id = d.s("id") ?: return
                val text = d.s("text") ?: return
                store.patchMessage(k, id) { it.copy(content = it.content + text) }
            }
            "message_done" -> {
                val m = e.data<Message>() ?: return
                store.finishMessage(m)
                if (m.role == "bot") alerts.reply(m)
            }
            "message_update" -> {
                val m = e.data<Message>() ?: return
                store.patchMessage(keyOf(m), m.id) { m }
                if (m.role == "card") alerts.card(m)
            }
            "bot" -> e.data<Bot>()?.let(store::upsertBot)
            "bot_removed" -> botId?.let(store::removeBot)
            "group" -> e.data<Group>()?.let(store::upsertGroup)
            "group_removed" -> groupId?.let(store::removeGroup)
            "queue" -> {
                val items = e["data"]?.let { runCatching { json.decodeFromJsonElement<List<QueueItem>>(it) }.getOrNull() } ?: return
                store.setQueue(groupId?.let(::groupKey) ?: botId ?: return, items)
            }
            "computer" -> {
                val c = e.data<ComputerStatus>() ?: return
                store.setComputer(botId ?: return, c)
            }
        }
    }
}
