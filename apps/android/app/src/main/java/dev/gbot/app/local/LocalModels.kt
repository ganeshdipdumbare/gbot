package dev.gbot.app.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
    )

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun files(ctx: Context): List<File> = dir(ctx).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".litertlm") && it.length() > 0 }
        ?.sortedBy { it.name }
        .orEmpty()

    fun id(f: File) = "local:" + f.name.removeSuffix(".litertlm")

    fun find(ctx: Context, id: String): File? =
        files(ctx).find { id(it) == id || it.name == id.removePrefix("local:") }

    /** Downloads that stopped partway, so they can be removed. */
    fun parts(ctx: Context): List<File> = dir(ctx).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".litertlm.part") }
        ?.sortedBy { it.name }
        .orEmpty()

    fun deletePart(ctx: Context, name: String) = File(dir(ctx), name).delete()

    fun delete(ctx: Context, id: String) {
        find(ctx, id)?.delete()
    }

    suspend fun download(ctx: Context, e: Entry, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(dir(ctx), e.file)
        val part = File(dir(ctx), e.file + ".part")
        val have = if (part.exists()) part.length() else 0L
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(e.url).get().apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Download failed (${res.code})")
            val total = if (res.code == 206) e.bytes else (res.body.contentLength().takeIf { it > 0 } ?: e.bytes)
            val out = java.io.FileOutputStream(part, have > 0 && res.code == 206)
            res.body.byteStream().use { input ->
                val buf = ByteArray(1 shl 16)
                var done = if (res.code == 206) have else 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
            out.close()
        }
        part.renameTo(target)
        target
    }
}
