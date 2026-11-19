package dev.gbot.app.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Models that live on the phone. One `.litertlm` file each, downloaded on
 * demand (they are far too big to ship in the APK).
 */
object LocalModels {
    data class Entry(val label: String, val file: String, val url: String, val bytes: Long, val note: String)

    val catalog = listOf(
        Entry(
            "Gemma 4 E4B", "gemma-4-E4B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            3_659_530_240, "Best tool use. Needs a recent phone with 8 GB or more.",
        ),
        Entry(
            "Gemma 4 E2B", "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            2_588_147_712, "Smaller and quicker; simpler tool use.",
        ),
        Entry(
            "Qwen 3.5 4B", "Qwen3.5-4B_mixed_int4.litertlm",
            "https://huggingface.co/litert-community/Qwen3.5-4B/resolve/main/Qwen3.5-4B_mixed_int4.litertlm",
            2_754_166_784, "Qwen 3.5, 4B, in a compact 4-bit build.",
        ),
    )

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun files(ctx: Context): List<File> = dir(ctx).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".litertlm") && it.length() > 0 }
        ?.sortedBy { it.name }
        .orEmpty()

    fun id(f: File) = "local:" + f.name.removeSuffix(".litertlm")

    fun find(ctx: Context, id: String): File? =
        files(ctx).find { id(it) == id || it.name == id.removePrefix("local:") }

    /** Downloads that stopped partway. They resume from where they stopped. */
    fun parts(ctx: Context): List<File> = dir(ctx).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".litertlm.part") }
        ?.sortedBy { it.name }
        .orEmpty()

    /** The partial file for a catalog entry, or null when nothing has been saved yet. */
    fun partFor(ctx: Context, e: Entry): File? = File(dir(ctx), e.file + ".part").takeIf { it.isFile && it.length() > 0 }

    fun deletePart(ctx: Context, name: String) = File(dir(ctx), name).delete()

    fun delete(ctx: Context, id: String) {
        find(ctx, id)?.delete()
    }

    /**
     * Downloads the model, continuing a partial file when there is one.
     * Cancelling the calling coroutine pauses it: the partial file is kept
     * so the next call picks up where this one stopped.
     */
    suspend fun download(ctx: Context, e: Entry, progress: (Long, Long) -> Unit): File =
        downloadTo(dir(ctx), e, progress)

    /** [download] into a given folder. Kept apart from the Context so it can be tested on the JVM. */
    suspend fun downloadTo(folder: File, e: Entry, progress: (Long, Long) -> Unit): File = try {
        downloadScope(folder, e, progress)
    } catch (ex: IOException) {
        // A pause or cancel closes the socket mid-read, which surfaces as an IOException.
        // If this coroutine was cancelled, report it as the cancel it is.
        currentCoroutineContext().ensureActive()
        throw ex
    }

    private suspend fun downloadScope(folder: File, e: Entry, progress: (Long, Long) -> Unit): File = coroutineScope {
        val target = File(folder, e.file)
        val part = File(folder, e.file + ".part")
        val have = if (part.exists()) part.length() else 0L
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(e.url).get().apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        val call = client.newCall(req)
        try {
            async(Dispatchers.IO) {
                call.execute().use { res ->
                    // The size the server reports for the whole file. The catalog size is only a fallback.
                    val serverTotal = res.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    // 416: the server has no bytes past the part. That is fine only if the part is the whole model.
                    if (res.code == 416 && have > 0) {
                        val expected = serverTotal ?: e.bytes
                        if (part.length() == expected) return@async finish(part, target)
                        // Nothing past this part can be fetched, so resuming it would fail the same way every time.
                        part.delete()
                        throw IllegalStateException("The saved part didn't match the server's file, so it was removed. Start the download again.")
                    }
                    if (!res.isSuccessful) throw IllegalStateException("Download failed (${res.code})")
                    val resumed = res.code == 206
                    val total = if (resumed) (serverTotal ?: e.bytes) else (res.body.contentLength().takeIf { it > 0 } ?: e.bytes)
                    var done = if (resumed) have else 0L
                    FileOutputStream(part, resumed).use { out ->
                        res.body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress(done, total)
                            }
                        }
                    }
                    // A connection that closes early must not become a "ready" model.
                    if (part.length() != total) throw IOException("Download stopped early (${part.length()} of $total bytes). Resume to finish it.")
                }
                finish(part, target)
            }.await()
        } finally {
            // Pausing or cancelling: closing the call wakes a read that is blocked on the network.
            call.cancel()
        }
    }

    private fun finish(part: File, target: File): File {
        if (!part.renameTo(target)) throw IOException("Could not save the model to ${target.name}")
        return target
    }
}
