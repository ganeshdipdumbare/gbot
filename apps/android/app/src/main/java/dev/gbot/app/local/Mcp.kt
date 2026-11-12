package dev.gbot.app.local

import android.content.Context
import dev.gbot.app.net.McpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP integrations over Streamable HTTP, the same protocol the server speaks
 * for tools. Each call opens a short session, initializes, then acts, so
 * nothing has to stay connected between messages.
 */
class Mcp(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("gbot", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val ids = AtomicLong(1)

    fun token(id: String): String? = prefs.getString("mcptoken_$id", null)
    fun putToken(id: String, token: String?) {
        if (token.isNullOrEmpty()) prefs.edit().remove("mcptoken_$id").remove("mcpoauth_$id").apply()
        else prefs.edit().putString("mcptoken_$id", token).apply()
    }

    /** A sign-in's tokens, kept in this app's private storage like every other key. */
    fun oauth(id: String): McpOAuth.Tokens? = McpOAuth.decode(prefs.getString("mcpoauth_$id", null))
    fun putOAuth(id: String, t: McpOAuth.Tokens) { prefs.edit().putString("mcpoauth_$id", McpOAuth.encode(t)).commit() }

    /**
     * The Authorization value for a server: a signed-in app's access token,
     * renewed first when it is about to expire (or [force]d after a 401),
     * else the pasted token.
     */
    private fun bearer(server: McpServer, force: Boolean = false): String? {
        val t = oauth(server.id) ?: return token(server.id)
        if (!force && !t.expired()) return t.access
        return synchronized(this) {
            // Another call may have renewed it while this one waited.
            val cur = oauth(server.id) ?: return@synchronized t.access
            if (cur.access != t.access && !cur.expired()) return@synchronized cur.access
            McpOAuth.refresh(cur).also { putOAuth(server.id, it) }.access
        }
    }

    /** Initialize and list the tools, so the picker can show them. */
    suspend fun probe(server: McpServer): McpServer = withContext(Dispatchers.IO) {
        runCatching {
            val session = Session(server)
            session.initialize()
            val listed = session.rpc("tools/list", buildJsonObject { }) ?: JsonObject(emptyMap())
            val tools = (listed["tools"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            server.copy(status = "ok", enabled = true, tools = tools)
        }.getOrElse {
            server.copy(status = "unreachable", tools = emptyList())
        }
    }

    /** Every tool from the enabled servers, named so a call can be routed back. */
    suspend fun schemas(servers: List<McpServer>): List<ToolSchema> = withContext(Dispatchers.IO) {
        servers.filter { it.enabled && it.status == "ok" }.flatMap { s ->
            val prefix = prefix(s.id)
            val session = runCatching {
                Session(s).also { it.initialize() }
            }.getOrNull() ?: return@flatMap emptyList()
            val listed = runCatching { session.rpc("tools/list", buildJsonObject { }) }.getOrNull() ?: return@flatMap emptyList()
            (listed["tools"] as? JsonArray).orEmpty().mapNotNull { t ->
                val o = t.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val desc = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(300)
                val params = (o["inputSchema"] as? JsonObject) ?: JsonObject(emptyMap())
                ToolSchema(prefix + name, "From ${s.name}: $desc", params)
            }
        }
    }

    suspend fun call(server: McpServer, tool: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        val s = Session(server)
        s.initialize()
        val res = s.rpc("tools/call", buildJsonObject {
            put("name", tool)
            put("arguments", args)
        }) ?: return@withContext "The integration returned nothing."
        val content = (res["content"] as? JsonArray).orEmpty().mapNotNull { c ->
            val o = c.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull == "text") o["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("\n")
        val err = res["isError"]?.jsonPrimitive?.contentOrNull == "true"
        (if (content.isBlank()) res.toString() else content).let { if (err) "The integration reported an error: $it" else it }
    }

    fun prefix(id: String) = "mc${id.takeLast(6)}__"

    private inner class Session(private val server: McpServer) {
        private var sessionId: String? = null

        fun initialize() {
            val res = rpc("initialize", buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject { put("name", "gbot"); put("version", "3") })
            }) ?: return
            // Some servers only accept the session after initialization.
            if (res.containsKey("protocolVersion") || res.containsKey("serverInfo")) {
                runCatching {
                    request(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", "notifications/initialized")
                    }, expectResponse = false)
                }
            }
        }

        fun rpc(method: String, params: JsonObject): JsonObject? =
            request(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", ids.getAndIncrement())
                put("method", method)
                put("params", params)
            }, expectResponse = true)

        private fun request(body: JsonObject, expectResponse: Boolean, renewed: Boolean = false): JsonObject? {
            val b = Request.Builder().url(server.url)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
            bearer(server, force = renewed)?.let { if (it.isNotBlank()) b.header("Authorization", "Bearer $it") }
            sessionId?.let { b.header("Mcp-Session-Id", it) }
            client.newCall(b.build()).execute().use { res ->
                // A signed-in app's token can die early: renew once and retry.
                if (res.code == 401 && !renewed && oauth(server.id) != null) return request(body, expectResponse, renewed = true)
                res.header("Mcp-Session-Id")?.let { sessionId = it }
                val text = res.body.string()
                if (!res.isSuccessful) throw IllegalStateException("${server.name} returned ${res.code} ${text.take(200)}")
                if (!expectResponse || text.isBlank()) return null
                val envelope = if (res.header("Content-Type")?.contains("text/event-stream") == true) {
                    firstEvent(text)
                } else {
                    Json.parseToJsonElement(text).jsonObject
                } ?: return null
                (envelope["error"] as? JsonObject)?.let {
                    throw IllegalStateException(it["message"]?.jsonPrimitive?.contentOrNull ?: "The integration returned an error")
                }
                return (envelope["result"] as? JsonObject) ?: JsonObject(emptyMap())
            }
        }

        /** Servers may answer over SSE; take the first JSON data line. */
        private fun firstEvent(text: String): JsonObject? {
            BufferedReader(text.reader()).useLines { lines ->
                for (line in lines) {
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val data = t.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val o = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    if (o.containsKey("result") || o.containsKey("error")) return o
                }
            }
            return null
        }
    }
}
