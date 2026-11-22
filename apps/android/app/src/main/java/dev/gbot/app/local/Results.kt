package dev.gbot.app.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The phone's copy of the server's result cards (server/internal/runs/
 * results.go). The bot calls show_ui with a list it has read from a tool
 * (its items), and Surfaces turns the checked list into A2UI cards. The same
 * rules apply here as on the server: an item needs
 * a title, links must be https, and a picture is fetched and kept on the
 * phone, never shown from a site the bot didn't give as https.
 */
class LocalResults(
    private val dir: File,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .publicOnly()
        .build(),
    /**
     * Whether a picture's address may be fetched: in a run, only one a tool
     * answered with or the person wrote, so the bot can't compose an
     * address that carries what it knows.
     */
    private val allowImage: (String) -> Boolean = { true },
    /** Whether a card may link to an address: the same rule, so a link can't carry what the bot knows. */
    private val allowLink: (String) -> Boolean = { true },
) {
    /** A set of cards, ready for the chat. */
    data class ResultSet(val title: String, val items: JsonArray)

    /** The cards for a show_results call, or null when fewer than two have a title. */
    fun build(args: JsonObject): ResultSet? {
        val title = text(args, "title")?.let { clip(it) }.orEmpty()
        val items = array(args["items"]).orEmpty().mapNotNull { it as? JsonObject }.take(MAX_CARDS)
        // Thumbnails download side by side, so one slow host can't hold the answer.
        val cards = runBlocking {
            items.map { async(Dispatchers.IO) { card(it) } }.awaitAll().filterNotNull()
        }
        if (cards.size < 2) return null
        return ResultSet(title, buildJsonArray { cards.forEach { add(it) } })
    }

    /** One item as a card, or null when it has no title. */
    internal fun card(item: JsonObject): JsonObject? {
        val title = text(item, "title")?.let { clip(it) } ?: return null
        val subtitle = text(item, "subtitle")?.let { clip(it) }
        val link = text(item, "link")?.takeIf { isHttps(it) && allowLink(it) }
        val kept = text(item, "image")?.takeIf { isHttps(it) && allowImage(it) }?.let { keepImage(it) }
        val fields = array(item["fields"]).orEmpty().mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val label = text(o, "label")?.let { clip(it) } ?: return@mapNotNull null
            val value = text(o, "value")?.let { clip(it) } ?: return@mapNotNull null
            label to value
        }.take(MAX_FIELDS)
        return buildJsonObject {
            put("title", title)
            subtitle?.let { put("subtitle", it) }
            link?.let { put("link", it) }
            kept?.let { (path, mime) ->
                put("file", path)
                put("mime", mime)
            }
            if (fields.isNotEmpty()) {
                put("fields", buildJsonArray {
                    fields.forEach { (label, value) ->
                        add(buildJsonObject { put("label", label); put("value", value) })
                    }
                })
            }
        }
    }

    /**
     * A picture for a show_ui surface: fetched once if the run may show it,
     * and kept on the phone. Returns the kept file's path, or null.
     */
    fun keep(src: String): String? = src.takeIf { isHttps(it) && allowImage(it) }?.let { keepImage(it) }?.first

    /** Fetches a thumbnail over https and keeps it under the app's files. */
    private fun keepImage(src: String): Pair<String, String>? {
        return runCatching {
            http.newCall(Request.Builder().url(src).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val mime = resp.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (!showableImage(mime)) return@use null
                val bytes = resp.body.capped(MAX_PICTURE.toLong())
                if (bytes.isEmpty()) return@use null
                dir.mkdirs()
                val file = File(dir, "res_" + UUID.randomUUID().toString().take(8) + extensionOf(mime))
                file.writeBytes(bytes)
                file.absolutePath to mime
            }
        }.getOrNull()
    }

    internal companion object {
        const val MAX_CARDS = 12
        const val MAX_FIELDS = 6
        const val MAX_TEXT = 160

        /**
         * Resolves a host to public addresses only, like the server's dialer:
         * a link or picture can't make the phone reach the LAN, the router or
         * a link-local address. Connections go to the addresses returned
         * here, so a host can't switch to a private one afterwards.
         */
        val PublicDns = Dns { host ->
            val addrs = Dns.SYSTEM.lookup(host).filter { isPublic(it) }
            if (addrs.isEmpty()) throw UnknownHostException("$host has no public address")
            addrs
        }

        fun isPublic(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
                a.isAnyLocalAddress || a.isMulticastAddress) return false
            val b = a.address
            if (b.size == 4) {
                val first = b[0].toInt() and 0xff
                val second = b[1].toInt() and 0xff
                // 0.0.0.0/8, and carrier NAT and Tailscale (100.64.0.0/10).
                if (first == 0 || (first == 100 && second in 64..127)) return false
            }
            // IPv6 unique local addresses (fc00::/7) are private too.
            return !(b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc)
        }

        /**
         * Checks the address actually connected to, on every hop. The
         * resolver check alone misses an IP-literal host (OkHttp doesn't
         * resolve those) and a redirect to one; this runs once the socket
         * is open and before the request is written.
         */
        val PublicConnection = Interceptor { chain ->
            val addr = chain.connection()?.socket()?.inetAddress
            if (addr != null && !isPublic(addr)) throw IOException("that address is on a private network")
            chain.proceed(chain.request())
        }

        fun isHttps(s: String): Boolean = runCatching {
            val u = URI(s.trim())
            u.scheme == "https" && !u.host.isNullOrEmpty() && s.length <= 2000
        }.getOrDefault(false)

        fun scalar(v: JsonElement?): String? {
            val p = v as? JsonPrimitive ?: return null
            if (p.isString) return p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            p.booleanOrNull?.let { return if (it) "yes" else "no" }
            // Plain digits at any size, the way the server prints a number.
            return p.doubleOrNull?.takeIf { it.isFinite() }?.let { d ->
                java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
            }
        }

        fun text(o: JsonObject, key: String): String? = scalar(o[key])

        /**
         * A list, or a list a model sent as its JSON text (some models
         * write a nested array as one string).
         */
        fun array(v: JsonElement?): JsonArray? = when (v) {
            is JsonArray -> v
            is JsonPrimitive -> if (v.isString) runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(v.content.trim()) as? JsonArray
            }.getOrNull() else null
            else -> null
        }

        fun clip(s: String): String {
            val t = s.trim()
            return if (t.length <= MAX_TEXT) t else t.take(MAX_TEXT - 1) + "…"
        }
    }
}

/**
 * Public internet only: public addresses from the resolver, the connected
 * address checked on every hop, no system proxy (it would connect for us and
 * skip both checks), and no redirect from https down to http.
 */
fun OkHttpClient.Builder.publicOnly(): OkHttpClient.Builder = this
    .dns(LocalResults.PublicDns)
    .addNetworkInterceptor(LocalResults.PublicConnection)
    .proxy(Proxy.NO_PROXY)
    .followSslRedirects(false)
