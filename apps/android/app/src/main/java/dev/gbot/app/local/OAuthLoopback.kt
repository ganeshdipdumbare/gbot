package dev.gbot.app.local

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * The sign-in's way back when an app won't send the browser to gbot://
 * (RFC 8252 loopback redirect). It listens on 127.0.0.1 for one callback,
 * then sends the browser on to gbot:// so the app comes back to the front.
 * It gives up after [timeoutMs].
 */
class OAuthLoopback(
    private val onCallback: (Map<String, String>) -> Unit,
    private val timeoutMs: Int = 10 * 60 * 1000,
) {
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val redirect: String = "http://127.0.0.1:${socket.localPort}/callback"

    fun start() {
        Thread({ serve() }, "gbot-oauth").apply { isDaemon = true }.start()
    }

    private fun serve() {
        socket.soTimeout = timeoutMs
        socket.use { server ->
            while (true) {
                val conn = try { server.accept() } catch (_: SocketTimeoutException) { return } catch (_: Exception) { return }
                conn.use { c ->
                    c.soTimeout = 10_000
                    val line = c.getInputStream().bufferedReader().readLine().orEmpty() // GET /callback?code=…&state=… HTTP/1.1
                    val target = line.split(" ").getOrNull(1).orEmpty()
                    if (!target.startsWith("/callback")) {
                        c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return@use
                    }
                    val params = parseQuery(target.substringAfter('?', ""))
                    val page = """<!doctype html><meta name="viewport" content="width=device-width"><title>gbot</title>
                        |<body style="font-family:sans-serif;text-align:center;padding:48px 24px">
                        |<p>${if (params["error"] != null) "The sign-in didn't finish." else "Signed in."} You can go back to gbot.</p>
                        |<script>location.replace("gbot://oauth/done")</script>""".trimMargin()
                    val bytes = page.toByteArray()
                    c.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes,
                    )
                    onCallback(params)
                    return
                }
            }
        }
    }

    fun close() = runCatching { socket.close() }

    companion object {
        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
