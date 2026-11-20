package dev.gbot.app.local

import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Reads at most [max] bytes of a response. A server that sends without end
 * fails the read instead of filling the phone's memory. Callers set the cap
 * generously: it bounds one answer, never how long a task runs.
 */
fun ResponseBody.capped(max: Long): ByteArray {
    val buf = Buffer()
    source().use { src ->
        while (buf.size <= max) {
            if (src.read(buf, 64 * 1024L) == -1L) return buf.readByteArray()
        }
    }
    throw IOException("the answer was larger than ${max / (1024 * 1024)} MB")
}

fun ResponseBody.cappedString(max: Long): String = String(capped(max), Charsets.UTF_8)
