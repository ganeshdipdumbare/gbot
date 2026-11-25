package dev.gbot.app.local

import java.net.URI

/**
 * Links in what a bot writes, found and set right before they are drawn.
 * Models write addresses loosely: bare in a sentence with a full stop after
 * them, in brackets, as `www.…` or `[name](example.com)` with no scheme, or
 * with a space left in. Each becomes an address a tap can open, or nothing.
 */
object Links {
    /** A link found in text: where it sits, what it shows, where it goes, and the address as written. */
    data class Found(val range: IntRange, val label: String, val url: String, val raw: String = url)

    private val MD_LINK = Regex("""\[([^\]\n]{1,300})]\(\s*<?([^)\s>]+(?:\s[^)\s>]+)*)>?\s*\)""")
    private val BARE = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'`]+""")
    private val DOMAIN = Regex("""(?i)^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\.[a-z]{2,}(?::\d{1,5})?(?:[/?#].*)?$""")
    private const val TRAILING = ".,;:!?'\"*_~"

    /**
     * An address a tap can open, from what the bot wrote as one: http(s),
     * mailto: or tel:, with a missing https:// put back and spaces encoded.
     * Null for anything else (a relative path, javascript:, a word).
     */
    fun normalize(raw: String): String? {
        var s = raw.trim().removePrefix("<").removeSuffix(">").trim()
        if (s.isEmpty() || s.length > 2000) return null
        val lower = s.lowercase()
        when {
            lower.startsWith("mailto:") -> return s.takeIf { '@' in it }
            lower.startsWith("tel:") -> return s.replace(" ", "").takeIf { it.length > 4 }
            lower.startsWith("http://") || lower.startsWith("https://") -> Unit
            lower.startsWith("//") -> s = "https:$s"
            lower.startsWith("www.") -> s = "https://$s"
            // example.com/page, but not a file name: report.pdf, notes.md
            // and clip.mov read as sites (.zip and .mov are real ones).
            DOMAIN.matches(s) && s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
                .substringAfterLast('.').lowercase() !in FILE_TYPES -> s = "https://$s"
            else -> return null
        }
        s = encode(s)
        return runCatching {
            val u = URI(s)
            s.takeIf { (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty() && '.' in u.host }
        }.getOrNull()
    }

    /**
     * Characters a browser takes but java.net.URI refuses (a space, `|`,
     * `[]` in a query, a lone `%`), written the way URI wants them, so a
     * real address isn't dropped for how a site spells it.
     */
    private fun encode(s: String): String = buildString {
        // Brackets belong only in the host (an IPv6 address); after it they are encoded.
        val hostEnd = s.indexOf("://").let { at -> if (at < 0) 0 else s.indexOfAny(charArrayOf('/', '?', '#'), at + 3).let { if (it < 0) s.length else it } }
        s.forEachIndexed { i, c ->
            when {
                c == '%' && !(i + 2 < s.length && s[i + 1].isHex() && s[i + 2].isHex()) -> append("%25")
                c in UNSAFE || c.code < 0x20 || (i >= hostEnd && (c == '[' || c == ']')) -> append('%').append("%02X".format(c.code))
                else -> append(c)
            }
        }
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    private const val UNSAFE = " |^{}`\\\"<>"

    /**
     * Endings that make a scheme-less name a file, not a site. Endings that
     * are mostly sites (.app, .sh, .io) are left out.
     */
    private val FILE_TYPES = setOf(
        "pdf", "zip", "mov", "mp4", "mp3", "md", "txt", "doc", "docx", "xls", "xlsx", "csv", "json", "xml", "html", "htm",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "js", "ts", "kt", "py", "apk", "dmg", "exe", "rar", "gz", "tar", "log",
    )

    /** A bare address with the punctuation of the sentence around it left off. */
    fun trimBare(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            val c = s.last()
            s = when {
                c in TRAILING -> s.dropLast(1)
                c == ')' && s.count { it == '(' } < s.count { it == ')' } -> s.dropLast(1)
                c == ']' && s.count { it == '[' } < s.count { it == ']' } -> s.dropLast(1)
                c == '}' && s.count { it == '{' } < s.count { it == '}' } -> s.dropLast(1)
                else -> return s
            }
        }
        return s
    }

    /** Bare addresses in plain text (no Markdown), in order. */
    fun bare(text: String): List<Found> = BARE.findAll(text).mapNotNull { m ->
        val shown = trimBare(m.value)
        val url = normalize(shown) ?: return@mapNotNull null
        Found(m.range.first until m.range.first + shown.length, shown, url, shown)
    }.toList()

    /**
     * Every link in a short text that may carry `[name](address)` links as
     * well as bare addresses, as a card's Text does. Links that don't
     * normalize are left out; their words stay as they were written.
     */
    fun inText(text: String): List<Found> {
        val out = ArrayList<Found>()
        val taken = ArrayList<IntRange>()
        for (m in MD_LINK.findAll(text)) {
            val url = normalize(m.groupValues[2]) ?: continue
            out += Found(m.range, m.groupValues[1].trim(), url, m.groupValues[2].trim())
            taken += m.range
        }
        for (f in bare(text)) if (taken.none { f.range.first in it }) out += f
        return out.sortedBy { it.range.first }
    }

    /** The site a link goes to, as it is shown beside a link in a card. */
    fun host(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
}
