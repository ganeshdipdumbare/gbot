package dev.gbot.app.local

import java.net.URI

/**
 * Links in what a bot writes, found and set right before they are drawn.
 * Models write addresses loosely: bare in a sentence with a full stop after
 * them, in brackets, as `www.…` or `[name](example.com)` with no scheme, or
 * with a space left in. Each becomes an address a tap can open, or nothing.
 */
object Links {
    /** A link found in text: where it sits, what it shows, and where it goes. */
    data class Found(val range: IntRange, val label: String, val url: String)

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
            lower.startsWith("www.") || DOMAIN.matches(s) -> s = "https://$s"
            else -> return null
        }
        s = s.replace(" ", "%20")
        return runCatching {
            val u = URI(s)
            s.takeIf { (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty() && '.' in u.host }
        }.getOrNull()
    }

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
        Found(m.range.first until m.range.first + shown.length, shown, url)
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
            out += Found(m.range, m.groupValues[1].trim(), url)
            taken += m.range
        }
        for (f in bare(text)) if (taken.none { f.range.first in it }) out += f
        return out.sortedBy { it.range.first }
    }

    /** The site a link goes to, as it is shown beside a link in a card. */
    fun host(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
}
