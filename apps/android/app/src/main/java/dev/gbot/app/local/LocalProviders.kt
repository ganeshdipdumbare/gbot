package dev.gbot.app.local

import android.content.Context
import dev.gbot.app.net.Message
import dev.gbot.app.net.Model
import dev.gbot.app.net.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One tool the model may ask for, in a form every provider can be told about. */
data class ToolSchema(val name: String, val description: String, val parameters: JsonObject)

data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

data class Turn(val text: String, val calls: List<ToolCall>)

/**
 * Providers you connect yourself. The keys stay in this app's private
 * preferences (Android encrypts app data at rest); they never go anywhere
 * except the provider you named.
 */
class LocalProviders(private val ctx: Context, private val db: LocalDb) {
    private val prefs = ctx.getSharedPreferences("gbot", Context.MODE_PRIVATE)
    // A reasoning model can think for minutes before the first byte, and a
    // long answer keeps arriving after that: give one request room rather
    // than cutting the task short. The run's own clock is the real bound.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    fun list(): List<Provider> = db.providers().map { it.copy(hasKey = key(it.id)?.isNotEmpty() == true) }

    fun get(id: String): Provider? = list().find { it.id == id }

    fun key(id: String): String? = prefs.getString("key_$id", null)

    suspend fun create(body: JsonObject): Provider {
        val id = "p" + UUID.randomUUID().toString().take(8)
        var p = Provider(
            id = id,
            name = body.str("name").ifBlank { "Provider" },
            kind = body.str("kind").ifBlank { "openai" },
            baseUrl = normalizeBase(body.str("kind"), body.str("baseUrl")),
            models = body.strings("models"),
            local = body.flag("local"),
            status = "connecting",
        )
        body.str("apiKey").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("key_$id", it).apply() }
        // Connecting with no models listed shouldn't leave a dead provider:
        // ask the endpoint what it has.
        if (p.models.isEmpty()) p = p.copy(models = runCatching { discover(p.kind, p.baseUrl, "", id) }.getOrElse { emptyList() })
        p = p.copy(status = if (p.models.isEmpty()) "no models" else "connected")
        db.putProvider(p)
        return p.copy(hasKey = key(id)?.isNotEmpty() == true)
    }

    suspend fun update(p: Provider, body: JsonObject): Provider {
        var next = p.copy(
            name = body.str("name").ifBlank { p.name },
            kind = body.str("kind").ifBlank { p.kind },
            baseUrl = normalizeBase(body.str("kind").ifBlank { p.kind }, body.str("baseUrl").ifBlank { p.baseUrl }),
            models = if (body.containsKey("models")) body.strings("models") else p.models,
            local = if (body.containsKey("local")) body.flag("local") else p.local,
        )
        if (body.flag("removeKey")) prefs.edit().remove("key_${p.id}").apply()
        body.str("apiKey").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("key_${p.id}", it).apply() }
        if (next.models.isEmpty()) next = next.copy(models = runCatching { discover(next.kind, next.baseUrl, "", next.id) }.getOrElse { emptyList() })
        next = next.copy(status = if (next.models.isEmpty()) "no models" else "connected")
        db.putProvider(next)
        return next.copy(hasKey = key(p.id)?.isNotEmpty() == true)
    }

    fun delete(id: String) {
        prefs.edit().remove("key_$id").apply()
        db.removeProvider(id)
    }

    /** Ask a provider what it has, without changing what it had on failure. */
    suspend fun refreshed(p: Provider): Provider {
        val found = runCatching { discover(p.kind, p.baseUrl, "", p.id) }
        return p.copy(
            models = found.getOrElse { p.models },
            status = if (found.isSuccess) "connected" else "error",
        )
    }

    /** Models these providers add to the picker, namespaced so they can't collide. */
    fun models(): List<Model> = list().filter { it.enabled }.flatMap { p ->
        p.models.map { m ->
            Model(id = "${p.id}:$m", label = m, provider = p.name, local = p.local, tools = true)
        }
    }

    suspend fun discover(kind: String, baseUrl: String, apiKey: String, id: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/').ifBlank { defaultBase(kind) }
            val key = apiKey.ifBlank { id?.let { key(it) }.orEmpty() }
            when (kind) {
                "anthropic" -> {
                    val body = get("$base/v1/models?limit=1000", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
                    (body["data"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                }
                "gemini" -> {
                    val body = get("$base/models?key=$key", emptyMap())
                    (body["models"] as? JsonArray).orEmpty().mapNotNull { m ->
                        val o = m.jsonObject
                        val methods = (o["supportedGenerationMethods"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                        if (methods.isEmpty() || "generateContent" in methods) o["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") else null
                    }
                }
                else -> {
                    val body = get("$base/models", if (key.isNotEmpty()) mapOf("Authorization" to "Bearer $key") else emptyMap())
                    val data = (body["data"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                    val models = (body["models"] as? JsonArray).orEmpty().mapNotNull {
                        val o = it.jsonObject
                        o["id"]?.jsonPrimitive?.contentOrNull ?: o["name"]?.jsonPrimitive?.contentOrNull
                    }
                    data + models
                }
            }.distinct().filter { it.isNotBlank() && !it.contains("embed") }.sorted()
        }

    /**
     * Runs one prompt to completion against the provider, executing tool calls
     * as they come. Returns the model's final text.
     */
    suspend fun run(
        p: Provider,
        model: String,
        system: String,
        history: List<Message>,
        prompt: String,
        tools: List<ToolSchema>,
        onText: (String) -> Unit,
        execute: suspend (ToolCall) -> String,
    ): String = withContext(Dispatchers.IO) {
        val key = key(p.id).orEmpty()
        val base = p.baseUrl.trimEnd('/').ifBlank { defaultBase(p.kind) }
        when (p.kind) {
            "anthropic" -> anthropic(base, key, model, system, history, prompt, tools, onText, execute)
            "gemini" -> gemini(base, key, model, system, history, prompt, tools, onText, execute)
            else -> openai(base, key, model, system, history, prompt, tools, onText, execute)
        }
    }

    private suspend fun openai(
        base: String, key: String, model: String, system: String, history: List<Message>, prompt: String,
        tools: List<ToolSchema>, onText: (String) -> Unit, execute: suspend (ToolCall) -> String,
    ): String {
        val messages = mutableListOf<JsonObject>()
        messages += buildJsonObject { put("role", "system"); put("content", system) }
        history.forEach { messages += wireOpenAI(it) }
        messages += buildJsonObject { put("role", "user"); put("content", prompt) }
        val toolJson = tools.map { t ->
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name); put("description", t.description); put("parameters", t.parameters)
                }
            }
        }
        var said = ""
        for (step in 0 until MAX_STEPS) {
            val last = step == MAX_STEPS - 1
            if (last) messages += buildJsonObject { put("role", "user"); put("content", WRAP_UP) }
            val body = buildJsonObject {
                put("model", model)
                put("stream", false)
                put("messages", JsonArray(messages))
                if (toolJson.isNotEmpty()) put("tools", JsonArray(toolJson))
            }
            val r = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
            val msg = r["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: return said.ifBlank { UNREADABLE }
            val text = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (text.isNotEmpty()) onText(text)
            if (text.isNotBlank()) said = text
            val calls = (msg["tool_calls"] as? JsonArray).orEmpty().mapNotNull { c ->
                val f = c.jsonObject["function"]?.jsonObject ?: return@mapNotNull null
                ToolCall(
                    id = c.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = f["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    arguments = parseObject(f["arguments"]?.jsonPrimitive?.contentOrNull),
                )
            }
            if (calls.isEmpty()) return said
            // The wrap-up step's tool calls are ignored: it was told to answer.
            if (last) return said.ifBlank { OUT_OF_STEPS }
            messages += buildJsonObject {
                put("role", "assistant")
                put("content", text)
                put("tool_calls", JsonArray((msg["tool_calls"] as? JsonArray).orEmpty().toList()))
            }
            calls.forEach { c ->
                messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", c.id)
                    put("content", execute(c))
                }
            }
        }
        return said.ifBlank { OUT_OF_STEPS }
    }

    private suspend fun anthropic(
        base: String, key: String, model: String, system: String, history: List<Message>, prompt: String,
        tools: List<ToolSchema>, onText: (String) -> Unit, execute: suspend (ToolCall) -> String,
    ): String {
        val messages = mutableListOf<JsonObject>()
        history.forEach { messages += wireAnthropic(it) }
        messages += buildJsonObject { put("role", "user"); put("content", prompt) }
        val toolJson = tools.map { t ->
            buildJsonObject { put("name", t.name); put("description", t.description); put("input_schema", t.parameters) }
        }
        var said = ""
        for (step in 0 until MAX_STEPS) {
            val last = step == MAX_STEPS - 1
            // The tool list stays on the last step: a conversation holding
            // tool_use blocks is rejected without it. The words do the work.
            if (last) messages += buildJsonObject { put("role", "user"); put("content", WRAP_UP) }
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", 4096)
                // The system prompt and the tool list are the same on every
                // step of a run and every turn of a chat; caching them cuts
                // the wait before the first word (and the bill with it).
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text")
                        put("text", system)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
                put("messages", JsonArray(messages))
                if (toolJson.isNotEmpty()) put("tools", JsonArray(toolJson))
            }
            val r = post("$base/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body)
            val blocks = (r["content"] as? JsonArray).orEmpty()
            val text = blocks.mapNotNull { b ->
                val o = b.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull == "text") o["text"]?.jsonPrimitive?.contentOrNull else null
            }.joinToString("")
            if (text.isNotEmpty()) onText(text)
            if (text.isNotBlank()) said = text
            val calls = blocks.mapNotNull { b ->
                val o = b.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapNotNull null
                ToolCall(
                    id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    arguments = (o["input"] as? JsonObject) ?: JsonObject(emptyMap()),
                )
            }
            if (calls.isEmpty()) return said
            if (last) return said.ifBlank { OUT_OF_STEPS }
            messages += buildJsonObject { put("role", "assistant"); put("content", JsonArray(blocks.toList())) }
            val results = buildJsonArray {
                calls.forEach { c ->
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", c.id)
                        put("content", execute(c))
                    }
                }
            }
            messages += buildJsonObject { put("role", "user"); put("content", results) }
        }
        return said.ifBlank { OUT_OF_STEPS }
    }

    private suspend fun gemini(
        base: String, key: String, model: String, system: String, history: List<Message>, prompt: String,
        tools: List<ToolSchema>, onText: (String) -> Unit, execute: suspend (ToolCall) -> String,
    ): String {
        val contents = mutableListOf<JsonObject>()
        history.forEach { contents += wireGemini(it) }
        contents += buildJsonObject { put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", prompt) } } }
        val toolJson = tools.map { t ->
            buildJsonObject { put("name", t.name); put("description", t.description); put("parameters", t.parameters) }
        }
        var said = ""
        for (step in 0 until MAX_STEPS) {
            val last = step == MAX_STEPS - 1
            if (last) contents += buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", WRAP_UP) } }
            }
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
                put("contents", JsonArray(contents))
                if (toolJson.isNotEmpty()) putJsonArray("tools") { addJsonObject { put("functionDeclarations", JsonArray(toolJson)) } }
            }
            val r = post("$base/models/$model:generateContent?key=$key", emptyMap(), body)
            val parts = r["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?: return said.ifBlank { UNREADABLE }
            val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
            if (text.isNotEmpty()) onText(text)
            if (text.isNotBlank()) said = text
            val calls = parts.mapNotNull { p ->
                val f = p.jsonObject["functionCall"]?.jsonObject ?: return@mapNotNull null
                ToolCall(
                    id = "",
                    name = f["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    arguments = (f["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                )
            }
            if (calls.isEmpty()) return said
            if (last) return said.ifBlank { OUT_OF_STEPS }
            contents += buildJsonObject {
                put("role", "model")
                putJsonArray("parts") {
                    if (text.isNotEmpty()) addJsonObject { put("text", text) }
                    calls.forEach { c ->
                        addJsonObject {
                            putJsonObject("functionCall") {
                                put("name", c.name)
                                put("args", c.arguments)
                            }
                        }
                    }
                }
            }
            contents += buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    calls.forEach { c ->
                        addJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", c.name)
                                putJsonObject("response") { put("result", c.let { execute(it) }) }
                            }
                        }
                    }
                }
            }
        }
        return said.ifBlank { OUT_OF_STEPS }
    }

    private fun wireOpenAI(m: Message): JsonObject =
        buildJsonObject { put("role", if (m.role == "user") "user" else "assistant"); put("content", m.content) }

    private fun wireAnthropic(m: Message): JsonObject = buildJsonObject {
        put("role", if (m.role == "user") "user" else "assistant")
        putJsonArray("content") { addJsonObject { put("type", "text"); put("text", m.content) } }
    }

    private fun wireGemini(m: Message): JsonObject = buildJsonObject {
        put("role", if (m.role == "user") "user" else "model")
        putJsonArray("parts") { addJsonObject { put("text", m.content) } }
    }

    private fun post(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) throw IllegalStateException("${res.code} ${text.take(300)}".trim())
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun get(url: String, headers: Map<String, String>): JsonObject {
        val req = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) throw IllegalStateException("${res.code} ${text.take(300)}".trim())
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    companion object {
        /**
         * Model/tool rounds in one answer. A real task — search, read,
         * compare, write — is many rounds, so the phone matches the server's
         * own bound rather than stopping a task halfway. The run's clock is
         * the real limit; this only stops a model that loops forever.
         */
        const val MAX_STEPS = 40

        /**
         * Said on the last round a run has. Without it a long task spends its
         * final round on another tool call and the person gets nothing: the
         * model must spend that round writing what it found instead.
         */
        const val WRAP_UP =
            "This is your last step: no more tools. Write your answer now from what you already have — " +
                "what you did, what you found, and anything still left for them."

        /** Said when even the wrap-up step produced no words. */
        const val OUT_OF_STEPS =
            "I ran out of steps before finishing this one. Ask me to carry on and I'll pick up where I stopped."

        /** Said when a provider answers with something this app can't read. */
        const val UNREADABLE = "The provider sent a reply I couldn't read. Check the endpoint in Settings → Models."

        /** "api.openai.com" means /v1; a path the person typed is kept as is. */
        fun normalizeBase(kind: String?, raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return defaultBase(kind)
            val url = runCatching { java.net.URI(t) }.getOrNull()
            val bare = url == null || url.path.isNullOrEmpty() || url.path == "/"
            val base = if (kind == "openai" && bare) t.trimEnd('/') + "/v1" else t.trimEnd('/')
            return base
        }

        fun defaultBase(kind: String?): String = when (kind) {
            "anthropic" -> "https://api.anthropic.com"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            else -> "https://api.openai.com/v1"
        }

        fun parseObject(s: String?): JsonObject = runCatching {
            Json.parseToJsonElement(s.orEmpty()).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
    }
}

private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.flag(k: String): Boolean = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull.toBoolean() }.getOrDefault(false) } ?: false
private fun JsonObject.strings(k: String): List<String> = (this[k] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
