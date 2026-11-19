package dev.gbot.app.local

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Quick web lookups without the browser window. A search is one request that
 * answers in about a second; the browser opens a page, reads it and clicks.
 * Both read only, and the same as the server's web_search and web_fetch.
 */
object WebLookup {
    const val SEARCH = "https://www.bing.com/search"
    const val MAX_HITS = 8
    const val MAX_TEXT = 12000
    const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    data class Hit(val title: String, val url: String, val snippet: String)

    private val algo = Regex("(?is)<li class=\"b_algo\"[^>]*>(.*?)</li>")
    private val head = Regex("(?is)<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>")
    private val snippet = Regex("(?is)<p[^>]*class=\"[^\"]*b_(?:lineclamp|algoSlug)[^\"]*\"[^>]*>(.*?)</p>|<div class=\"b_caption[^\"]*\"[^>]*>.*?<p[^>]*>(.*?)</p>")
    private val tag = Regex("(?s)<[^>]*>")
    private val title = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val ogImage = Regex("(?is)<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*>")
    private val content = Regex("(?is)content=[\"']([^\"']+)[\"']")
    private val dropped = Regex("(?is)<(script|style|noscript|svg|template|head)\\b[^>]*>.*?</(script|style|noscript|svg|template|head)\\s*>|<!--.*?-->")
    private val breaks = Regex("(?i)</?(br|p|div|li|h[1-6]|tr|table|section|article)\\b[^>]*>")

    private val link = Regex("https://[^\\s<>\"']+")

    /** https links the person wrote. Trailing punctuation goes; a closing ) stays when the link has its own ( . */
    fun linksIn(text: String): Set<String> = link.findAll(text).take(20).map { m ->
        var u = m.value
        while (u.isNotEmpty() && (u.last() in ".,;:!?\"'" || (u.last() == ')' && u.count { it == ')' } > u.count { it == '(' }))) u = u.dropLast(1)
        u
    }.toSet()

    /** One spelling of a page address, so a pasted link and the bot's encoded copy of it match. */
    fun key(u: String): String = (runCatching { URI(u.trim()).toASCIIString() }.getOrDefault(u.trim())).trimEnd('/')

    fun searchUrl(query: String): String = SEARCH + "?setlang=en&q=" + URLEncoder.encode(query.trim(), "UTF-8")

    fun parseSearch(page: String): List<Hit> {
        val out = ArrayList<Hit>()
        for (m in algo.findAll(page)) {
            if (out.size == MAX_HITS) break
            val h = head.find(m.groupValues[1]) ?: continue
            val url = target(unescape(h.groupValues[1])) ?: continue
            val sn = snippet.find(m.groupValues[1])
            out += Hit(plain(h.groupValues[2]), url, if (sn == null) "" else plain(sn.groupValues[1] + sn.groupValues[2]))
        }
        return out
    }

    fun format(hits: List<Hit>): String =
        if (hits.isEmpty()) "No results. Try different words."
        else hits.mapIndexed { i, h -> "${i + 1}. ${h.title}\n   ${h.url}" + if (h.snippet.isBlank()) "" else "\n   ${h.snippet}" }.joinToString("\n")

    /** Bing wraps each link in a tracker whose u parameter is the page, base64, after "a1". */
    internal fun target(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (host == "www.bing.com" && uri.path.orEmpty().startsWith("/ck/")) {
            val u = uri.rawQuery.orEmpty().split("&").firstOrNull { it.startsWith("u=") }?.drop(2) ?: return null
            val enc = URLDecoder.decode(u, "UTF-8").removePrefix("a1").trimEnd('=')
            val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull() ?: return null
            return https(dec)
        }
        if (host.endsWith("bing.com")) return null
        return https(raw)
    }

    internal fun https(s: String): String? {
        val uri = runCatching { URI(s.trim()) }.getOrNull() ?: return null
        return if (uri.scheme == "https" && !uri.host.isNullOrEmpty() && s.length <= 2000) uri.toString() else null
    }

    /** A page as its title, its main picture and readable text. */
    fun pageText(base: String, page: String): String {
        val sb = StringBuilder()
        title.find(page)?.let { sb.appendLine("Title: " + plain(it.groupValues[1])) }
        ogImage.find(page)?.let { m ->
            content.find(m.value)?.let { c ->
                val abs = runCatching { URI(base).resolve(unescape(c.groupValues[1]).trim()).toString() }.getOrNull()?.let(::https)
                if (abs != null) sb.appendLine("Image: $abs")
            }
        }
        val body = unescape(tag.replace(breaks.replace(dropped.replace(page, ""), "\n"), ""))
            .replace(Regex("[ \\t\\r]+"), " ").replace(Regex(" ?\\n ?"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        val text = sb.toString() + "\n" + body.take(MAX_TEXT)
        return text.trim().ifBlank { "That page had no readable text." }
    }

    internal fun plain(s: String): String = unescape(tag.replace(s, "")).replace(Regex("\\s+"), " ").trim()

    internal fun unescape(s: String): String = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")
}
