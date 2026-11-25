package dev.gbot.app.local

/**
 * Cards by default. Models reach for Markdown even when show_ui would read
 * far better, so when a run is about to end with an answer made of facts —
 * it looked something up, or the reply is a table, a list of numbers, a few
 * "Label: value" lines — and it showed no card, it is asked once to show
 * one. Conversation, explanations and code pass straight through, and the
 * model may still send its reply unchanged when a card doesn't fit.
 */
object CardNudge {
    const val ASK =
        "Before you send this: the person takes in facts like these better as a card than as text. " +
            "Call show_ui now — a glance card (a Card holding a Column: h4 for what it is, h1 for the main value, captions for the details) " +
            "or items for a list of things to compare — then reply in a line or two of prose that doesn't repeat what the card shows. " +
            "If this answer really is conversation, an explanation, advice or code, send your reply again unchanged and call nothing."

    /**
     * Tools that read facts worth a card. Writes (calendar_add, email_draft)
     * are left out: their confirmation is a sentence, not a card. Every
     * integration tool counts; a plain confirmation from one has no
     * figures worth laying out and passes [check] anyway.
     */
    private val LOOKUPS = setOf(
        "web_search", "web_fetch", "browser_read", "contact_search", "calendar_list", "email_list", "email_read",
        "recall", "routine_list", "bot_list",
    )

    fun isLookup(tool: String, mcp: Boolean): Boolean = mcp || tool in LOOKUPS

    /**
     * The words to send back before [reply] goes out, or null to let it go.
     * [looked] is whether the run read facts from a lookup tool; [shown]
     * whether it already drew a card or asked on one.
     */
    fun check(reply: String, looked: Boolean, shown: Boolean): String? {
        val text = reply.trim()
        if (shown || text.isEmpty()) return null
        if ("```" in text) return null
        // An essay or a long write-up stays text: a card can't hold it.
        if (text.length > 2500) return null
        val structured = looksStructured(text)
        if (!looked && !structured) return null
        // A short, plain sentence after a lookup ("Done, it's saved.") has
        // nothing to lay out.
        if (!structured && !hasFigures(text)) return null
        return ASK
    }

    internal fun looksStructured(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.count { it.startsWith("|") && it.endsWith("|") } >= 3) return true
        val bullets = lines.filter { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") || NUMBERED.matches(it) }
        if (bullets.size >= 3 && bullets.count { hasFigures(it) } >= 2) return true
        val pairs = lines.count { PAIR.containsMatchIn(it) }
        return pairs >= 3
    }

    /**
     * Numbers that carry meaning: a price, a time, a temperature, a
     * percentage, a score — or at least three numbers, as a summary of
     * figures has. "I saved 1 contact" has none worth a card.
     */
    internal fun hasFigures(text: String): Boolean {
        val plain = URL.replace(text, " ")
        return UNIT.containsMatchIn(plain) || NUMBER.findAll(plain).count() >= 3
    }

    private val NUMBERED = Regex("""^\d+[.)]\s.*""")
    // "Label: value", but not an address ("https://…" has a label and a colon too).
    private val PAIR = Regex("""^[-*•]?\s*\**[\p{L}][\p{L} /]{1,24}\**\s*:(?!//)\s*\**\S""")
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val NUMBER = Regex("""\d+(?:[.,:]\d+)*""")
    private val UNIT = Regex(
        """(?i)[$€£¥₹]\s?\d|\d\s?(?:[$€£¥₹%°]|km\b|mi\b|kg\b|min\b|h\b|hrs?\b|mins?\b|am\b|pm\b|usd\b|eur\b|gbp\b)|\b\d{1,2}:\d{2}\b|\b\d+\s?[-–:]\s?\d+\b""",
    )
}
