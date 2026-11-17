package dev.gbot.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.gbot.app.GbotApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures a tool handed back, as the chat shows them. On the phone's own
 * engine they are files in the app's storage; with a gbot server they come
 * from /api/blobs, which only this person's session may read. Decoded
 * pictures are kept in a small cache so scrolling doesn't re-read them.
 */
object Pictures {
    private const val MAX_EDGE = 1600

    private val cache = object : LruCache<String, ImageBitmap>(24) {}

    suspend fun load(key: String, file: String?, blob: String?): ImageBitmap? {
        cache.get(key)?.let { return it }
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    !file.isNullOrEmpty() -> File(file).takeIf { it.isFile }?.readBytes()
                    !blob.isNullOrEmpty() -> {
                        val app = GbotApp.instance
                        app.http.client.newCall(app.http.request("/api/blobs/$blob").get().build()).execute().use { res ->
                            if (res.isSuccessful) res.body.bytes() else null
                        }
                    }
                    else -> null
                }
            }.getOrNull()
        } ?: return null
        val img = withContext(Dispatchers.Default) { decode(bytes) } ?: return null
        cache.put(key, img)
        return img
    }

    /** Decode no larger than the screen needs: a diagram can be huge. */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }
}
