package dev.gbot.app.local

import android.content.Context
import android.util.Log
import dev.gbot.app.net.SlackInstall
import dev.gbot.app.net.SlackLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Where a reply to a Slack message goes. */
@Serializable
data class SlackTarget(val installId: String, val channel: String, val thread: String)

/**
 * One Slack message, as gbot reads it. The same rules as the server
 * (server/internal/slack/slack.go): bots' messages, edits and joins are
 * not requests, and mentions are taken out of the text.
 */
data class SlackEvent(
    val type: String = "",
    val subtype: String = "",
    val user: String = "",
    val botId: String = "",
    val text: String = "",
    val channel: String = "",
    val ts: String = "",
    val threadTs: String = "",
) {
    /** The thread the message started, or the message itself. */
    val thread: String get() = threadTs.ifEmpty { ts }

    val prompt: String get() = text.replace(Regex("<@[A-Z0-9]+>"), " ").replace(Regex("[ \\t]+"), " ").trim()

    /** Anything a bot posted is ignored, gbot's own replies included, or two bots would talk forever. */
    val actionable: Boolean get() =
        botId.isEmpty() && user.isNotEmpty() && subtype.isEmpty() && (type == "app_mention" || type == "message") && prompt.isNotEmpty()

    companion object {
        fun from(o: JsonObject): SlackEvent {
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return SlackEvent(s("type"), s("subtype"), s("user"), s("bot_id"), s("text"), s("channel"), s("ts"), s("thread_ts"))
        }
    }
}

/**
 * Slack, on the phone. A phone can't take Slack's webhooks, so it uses
 * Socket Mode: the app opens the connection to Slack and events arrive on
 * it. That needs an app-level token (xapp-, connections:write) as well as
 * the bot token (xoxb-, app_mentions:read and chat:write). Tokens stay in
 * this app's private storage, like every other key on the phone.
 */
class LocalSlack(
    ctx: Context,
    private val db: LocalDb,
    private val scope: CoroutineScope,
    /** A linked channel mentioned a bot: start its run. */
    private val onMessage: (SlackInstall, SlackLink, SlackEvent) -> Unit,
    private val base: String = "https://slack.com/api",
) {
    private val prefs = ctx.getSharedPreferences("gbot", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // the socket stays open
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val sockets = ConcurrentHashMap<String, Job>()
    private val live = ConcurrentHashMap<String, WebSocket>()
    private val connected = ConcurrentHashMap.newKeySet<String>()
    // Slack can redeliver an event; acting twice would run the request twice.
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    fun installs(): List<SlackInstall> = db.get(KEY)?.let {
        runCatching { json.decodeFromString(ListSerializer(SlackInstall.serializer()), it) }.getOrNull()
    }.orEmpty().map { it.copy(status = if (it.id in connected) "listening" else "connecting") }

    private fun save(list: List<SlackInstall>) =
        db.set(KEY, json.encodeToString(ListSerializer(SlackInstall.serializer()), list.map { it.copy(status = "") }))

    private fun botToken(id: String) = prefs.getString("slackbot_$id", null).orEmpty()
    private fun appToken(id: String) = prefs.getString("slackapp_$id", null).orEmpty()

    /** Check both tokens with Slack, then save the workspace and start listening. */
    fun connect(botToken: String, appToken: String): SlackInstall {
        if (!botToken.startsWith("xoxb-")) throw IllegalArgumentException("The bot token starts with xoxb-")
        if (!appToken.startsWith("xapp-")) throw IllegalArgumentException("The app-level token starts with xapp-")
        val auth = call("auth.test", botToken)
        val teamId = auth.str("team_id")
        if (installs().any { it.teamId == teamId }) throw IllegalStateException("That Slack workspace is already connected")
        // Opening a connection proves the app token works and Socket Mode is on.
        call("apps.connections.open", appToken)
        val install = SlackInstall(
            id = "sl_" + UUID.randomUUID().toString().take(8), teamId = teamId,
            teamName = auth.str("team"), createdAt = Instant.now().toString(),
        )
        prefs.edit().putString("slackbot_${install.id}", botToken).putString("slackapp_${install.id}", appToken).commit()
        save(installs() + install)
        listen(install.id)
        return install
    }

    fun disconnect(id: String) {
        stop(id)
        prefs.edit().remove("slackbot_$id").remove("slackapp_$id").apply()
        save(installs().filter { it.id != id })
    }

    fun link(id: String, channelId: String, channel: String, botId: String): SlackLink {
        val install = installs().find { it.id == id } ?: throw IllegalStateException("That workspace is gone")
        if (channelId.isBlank() || botId.isBlank()) throw IllegalArgumentException("A channel and a bot are both needed")
        val l = SlackLink(installId = id, channelId = channelId.trim(), channel = channel.trim().removePrefix("#"), botId = botId, createdAt = Instant.now().toString())
        save(installs().map { if (it.id == id) it.copy(links = install.links.filter { x -> x.channelId != l.channelId } + l) else it })
        return l
    }

    fun unlink(id: String, channelId: String) =
        save(installs().map { if (it.id == id) it.copy(links = it.links.filter { l -> l.channelId != channelId }) else it })

    /** Post a reply into the thread that asked. */
    fun post(t: SlackTarget, text: String) {
        val token = botToken(t.installId).ifEmpty { return }
        runCatching {
            val body = buildJsonObject {
                put("channel", t.channel)
                put("text", text.take(39_000))
                if (t.thread.isNotEmpty()) put("thread_ts", t.thread)
            }
            val req = Request.Builder().url("$base/chat.postMessage")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(req).execute().use { res -> check(res, res.body.string()) }
        }.onFailure { Log.w(TAG, "slack post failed", it) }
    }

    /** Listen on every connected workspace. */
    fun start() = installs().forEach { listen(it.id) }

    fun stop() = sockets.keys.toList().forEach(::stop)

    fun any(): Boolean = installs().isNotEmpty()

    private fun stop(id: String) {
        sockets.remove(id)?.cancel()
        live.remove(id)?.close(1000, null)
        connected.remove(id)
    }

    /** Keep one Socket Mode connection per workspace, reconnecting with backoff. */
    private fun listen(id: String) {
        if (sockets[id]?.isActive == true) return
        sockets[id] = scope.launch(Dispatchers.IO) {
            var wait = 2_000L
            while (true) {
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                val ok = runCatching {
                    val url = call("apps.connections.open", appToken(id)).str("url")
                    live[id] = http.newWebSocket(Request.Builder().url(url).build(), Listener(id, closed))
                }.isSuccess
                if (ok) {
                    closed.await()
                    wait = 2_000L
                }
                connected.remove(id)
                if (installs().none { it.id == id }) return@launch
                delay(wait)
                wait = (wait * 2).coerceAtMost(60_000L)
            }
        }
    }

    private inner class Listener(private val id: String, private val closed: kotlinx.coroutines.CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            // Every envelope is acknowledged at once, or Slack sends it again.
            o.str("envelope_id").takeIf { it.isNotEmpty() }?.let { webSocket.send("""{"envelope_id":"$it"}""") }
            when (o.str("type")) {
                "hello" -> connected.add(id)
                "disconnect" -> webSocket.close(1000, null) // Slack is rotating the connection
                "events_api" -> handle(o["payload"] as? JsonObject ?: return)
            }
        }

        private fun handle(payload: JsonObject) {
            val e = SlackEvent.from(payload["event"] as? JsonObject ?: return)
            if (!e.actionable) return
            // One message can arrive twice: as app_mention and as message,
            // or again after a reconnect. It is answered once.
            if (!seen.add("${e.channel}:${e.ts}")) return
            synchronized(seen) { while (seen.size > 500) seen.remove(seen.first()) }
            val install = installs().find { it.id == id } ?: return
            val link = install.links.find { it.channelId == e.channel } ?: return
            onMessage(install, link, e)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "slack socket closed", t)
            closed.complete(Unit)
        }
    }

    private fun call(method: String, token: String): JsonObject {
        val req = Request.Builder().url("$base/$method")
            .header("Authorization", "Bearer $token")
            .post(FormBody.Builder().build())
            .build()
        return http.newCall(req).execute().use { res -> check(res, res.body.string()) }
    }

    /** Slack answers 200 with ok:false for real failures. */
    private fun check(res: Response, text: String): JsonObject {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalStateException("Slack answered ${res.code}")
        if ((o["ok"] as? JsonPrimitive)?.contentOrNull != "true") {
            throw IllegalStateException(slackError(o.str("error")))
        }
        return o
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        private const val TAG = "gbot-slack"
        private const val KEY = "slack"

        fun slackError(code: String): String = when (code) {
            "invalid_auth", "not_authed", "token_revoked" -> "Slack didn't accept that token"
            "not_allowed_token_type" -> "That's the wrong kind of token: the bot token starts with xoxb-, the app-level token with xapp-"
            "missing_scope" -> "The token is missing a scope: the app-level token needs connections:write"
            "not_in_channel", "channel_not_found" -> "Invite the Slack app to that channel first"
            else -> "Slack said: ${code.ifEmpty { "something went wrong" }}"
        }
    }
}
