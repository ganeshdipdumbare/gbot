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
        // Pinned to one revision: the size check below and libcactus 4.0.0 both
        // expect exactly this file, and `main` can move under them.
        "https://huggingface.co/Cactus-Compute/needle3/resolve/c95d25b4efa1e9601e785d92438e4ade0dbddbf8/needle3.cact",
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

    /**
     * Forgets the tool schema after the weights are removed. Cactus's C API
     * has no unload call, so the model already in memory stays until the
     * process ends; [loaded] is kept on purpose, so downloading the same
     * pinned file again reuses that copy instead of loading a second one.
     */
    fun unload() = synchronized(lock) { toolsKey = null }

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
                    check(rc >= 0) { "load failed ($rc): ${lastError()}" }
                    loaded = file
                    toolsKey = null
                }
                val schema = toolsJson(tools)
                if (toolsKey != schema) {
                    val rc = nativeInit(utf8(""), utf8(schema))
                    check(rc >= 0) { "init failed ($rc): ${lastError()}" }
                    toolsKey = schema
                }
                nativeReset()
                val raw = nativeComplete(utf8(request.take(1000)), 256)?.let(::text) ?: error("complete failed: ${lastError()}")
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

    // Text crosses JNI as standard UTF-8 bytes (see needle.cpp): emoji and a
    // reply cut off mid-character decode safely, with U+FFFD at worst.
    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun text(b: ByteArray) = String(b, Charsets.UTF_8)
    private fun lastError(): String = text(nativeLastError())

    private external fun nativeLoad(path: String): Int
    private external fun nativeInit(system: ByteArray, tools: ByteArray): Int
    private external fun nativeComplete(input: ByteArray, maxTokens: Int): ByteArray?
    private external fun nativeReset()
    private external fun nativeLastError(): ByteArray
}
