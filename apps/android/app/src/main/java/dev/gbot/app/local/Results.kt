package dev.gbot.app.local

import kotlinx.serialization.json.Json
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The phone's copy of the server's result cards (server/internal/runs/
 * results.go): when a tool hands back a list of records, the chat shows
 * them as cards. The same rules apply here: a record needs a title, links
 * must be https, and a picture is fetched and kept on the phone, never
 * shown from a site the tool named.
 */
class LocalResults(
    private val dir: File,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .dns(PublicDns)
        // A system proxy would connect for us, so the public-address rule
        // above would not hold. Go direct.
        .proxy(Proxy.NO_PROXY)
        .build(),
) {
    /** A set of cards, or null when the answer is not a list of records. */
    data class ResultSet(val title: String, val items: JsonArray)

    fun build(raw: String): ResultSet? {
        val (title, found) = rows(raw)
        if (found.size < 2) return null
        // Cards are built side by side, so the thumbnails download together.
        val cards = runBlocking {
            found.take(MAX_CARDS).map { async(Dispatchers.IO) { card(it) } }.awaitAll().filterNotNull()
        }
        if (cards.size < 2) return null
        return ResultSet(title, buildJsonArray { cards.forEach { add(it) } })
    }

    /** The list of records in a structured answer, and its title. */
    internal fun rows(raw: String): Pair<String, List<JsonObject>> {
        val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return "" to emptyList()
        return when (el) {
            is JsonArray -> "" to objects(el)
            is JsonObject -> {
                val title = clip(text(el, "title").orEmpty())
                for (k in el.keys.sorted()) {
                    val arr = el[k] as? JsonArray ?: continue
                    val found = objects(arr)
                    if (found.isNotEmpty()) return title to found
                }
                "" to emptyList()
            }
            else -> "" to emptyList()
        }
    }

    /** One record as a card, or null when it has no title. */
    internal fun card(row: JsonObject): JsonObject? {
        val used = mutableSetOf<String>()
        fun pick(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            scalar(row[k])?.also { used += k }
        }
        val title = pick(*TITLE) ?: return null
        val subtitle = pick(*SUBTITLE)
        val link = firstHttps(row, LINK, used)
        val image = firstHttps(row, IMAGE, used)
        val kept = image?.let { keepImage(it) }
        return buildJsonObject {
            put("title", clip(title))
            subtitle?.let { put("subtitle", clip(it)) }
            link?.let { put("link", it) }
            kept?.let { (path, mime) ->
                put("file", path)
                put("mime", mime)
            }
            val fields = row.keys.sorted().filter { it !in used }.mapNotNull { k ->
                scalar(row[k])?.let { k to it }
            }.take(MAX_FIELDS)
            if (fields.isNotEmpty()) {
                put("fields", buildJsonArray {
                    fields.forEach { (k, v) ->
                        add(buildJsonObject { put("label", humanize(k)); put("value", clip(v)) })
                    }
                })
            }
        }
    }

    /** Fetches a thumbnail over https and keeps it under the app's files. */
    private fun keepImage(src: String): Pair<String, String>? {
        if (!isHttps(src)) return null
        // OkHttp doesn't ask the resolver for an IP-literal host, so check
        // a literal address here.
        val host = URI(src).host.trim('[', ']')
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
            val literal = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
            if (!isPublic(literal)) return null
        }
        return runCatching {
            http.newCall(Request.Builder().url(src).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val mime = resp.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (!showableImage(mime)) return@use null
                val bytes = resp.body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_PICTURE) return@use null
                dir.mkdirs()
                val file = File(dir, "res_" + UUID.randomUUID().toString().take(8) + extensionOf(mime))
                file.writeBytes(bytes)
                file.absolutePath to mime
            }
        }.getOrNull()
    }

    /** The first key holding an https address; other values are never used. */
    private fun firstHttps(row: JsonObject, keys: Array<String>, used: MutableSet<String>): String? {
        for (k in keys) {
            val v = scalar(row[k]) ?: continue
            if (!isHttps(v)) continue
            used += k
            return v
        }
        return null
    }

    private fun objects(a: JsonArray): List<JsonObject> = a.mapNotNull { it as? JsonObject }

    internal companion object {
        const val MAX_CARDS = 12
        const val MAX_FIELDS = 6
        const val MAX_TEXT = 160
        val TITLE = arrayOf("title", "name", "label")
        val SUBTITLE = arrayOf("subtitle", "location", "address", "city", "description", "summary")
        val LINK = arrayOf("url", "link", "href")
        val IMAGE = arrayOf("image", "image_url", "imageUrl", "thumbnail", "thumbnail_url", "photo", "picture")

        /**
         * Resolves a host to public addresses only, like the server's dialer:
         * a tool's answer can't make the phone reach the LAN, the router or
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
            // IPv6 unique local addresses (fc00::/7) are private too.
            return !(b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc)
        }

        fun isHttps(s: String): Boolean = runCatching {
            val u = URI(s)
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

        fun humanize(k: String): String {
            val s = k.replace('_', ' ').replace('-', ' ').trim()
            return if (s.isEmpty()) k else s.substring(0, 1).uppercase() + s.substring(1)
        }

        fun clip(s: String): String {
            val t = s.trim()
            return if (t.length <= MAX_TEXT) t else t.take(MAX_TEXT - 1) + "…"
        }
    }
}
