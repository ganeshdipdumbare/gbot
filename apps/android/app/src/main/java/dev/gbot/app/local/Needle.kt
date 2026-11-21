package dev.gbot.app.local

import android.content.Context
import android.util.Log
import dev.gbot.app.net.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** One tool call Needle chose, and how sure it was. */
data class NeedleCall(val name: String, val arguments: JsonObject, val confidence: Double)

/**
 * Needle (Cactus Compute): a ~35 MB on-device model that does one thing —
 * pick the tool call for a short request. It cannot write an answer, so it
 * is a first pass in front of the chat model, never a replacement. Runs
 * fully on the phone, no key, no network after the weights are downloaded.
 *
 * The runtime keeps one model per process, so every call goes through
 * [lock]. 64-bit ARM only; elsewhere [available] is false and everything
 * here is a no-op.
 */
object Needle {
    private const val TAG = "gbot.needle"
    val entry = LocalModels.Entry(
        "Needle 3", "needle3.cact",
        "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact",
        35_335_380, "Picks tools on the phone, fast and offline.",
    )

    private val lock = Any()
    private var loaded: String? = null
    private var toolsKey: String? = null

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("gbotneedle") }.isSuccess
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, "needle").apply { mkdirs() }
    fun weights(ctx: Context): File = File(dir(ctx), entry.file)
    fun ready(ctx: Context) = available && weights(ctx).let { it.isFile && it.length() == entry.bytes }

    suspend fun download(ctx: Context, progress: (Long, Long) -> Unit): File =
        LocalModels.downloadTo(dir(ctx), entry, progress)

    fun unload() = synchronized(lock) { loaded = null; toolsKey = null }

    /**
     * The tool Needle would call for [request], or null when it has no
     * confident call. [tools] are the same schemas the chat model sees.
     */
    fun route(ctx: Context, request: String, tools: List<ToolSchema>, minConfidence: Double = 0.4): NeedleCall? {
        if (!ready(ctx) || tools.isEmpty() || request.isBlank()) return null
        return runCatching {
            synchronized(lock) {
                val file = weights(ctx).absolutePath
                if (loaded != file) {
                    val rc = nativeLoad(file)
                    check(rc >= 0) { "load failed ($rc): ${nativeLastError()}" }
                    loaded = file
                    toolsKey = null
                }
                val schema = toolsJson(tools)
                if (toolsKey != schema) {
                    val rc = nativeInit("", schema)
                    check(rc >= 0) { "init failed ($rc): ${nativeLastError()}" }
                    toolsKey = schema
                }
                nativeReset()
                val raw = nativeComplete(request.take(1000), 256) ?: error("complete failed: ${nativeLastError()}")
                Log.d(TAG, "raw: ${raw.take(400)}")
                parse(raw, tools.map { it.name }.toSet(), minConfidence)
            }
        }.onFailure { Log.w(TAG, "route failed", it) }.getOrNull()
    }

    internal fun toolsJson(tools: List<ToolSchema>): String = JsonArray(tools.map { t ->
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("parameters", t.parameters)
            })
        }
    }).toString()

    /** Reads the engine's JSON: `function_calls` plus a `confidence`. */
    internal fun parse(raw: String, known: Set<String>, minConfidence: Double): NeedleCall? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val confidence = (root["confidence"] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (confidence < minConfidence) return null
        val calls = (root["function_calls"] as? JsonArray).orEmpty()
        // One clear call only: two at once means it is guessing at a plan.
        val call = calls.singleOrNull()?.jsonObject ?: return null
        val name = (call["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (name !in known) return null
        val args = (call["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        return NeedleCall(name, args, confidence)
    }

    private external fun nativeLoad(path: String): Int
    private external fun nativeInit(system: String, tools: String): Int
    private external fun nativeComplete(input: String, maxTokens: Int): String?
    private external fun nativeReset()
    private external fun nativeLastError(): String
}
