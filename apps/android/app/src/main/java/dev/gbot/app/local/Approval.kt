package dev.gbot.app.local

/** Whether a tool step runs, asks, or is refused. Kept pure so it can be tested. */
object Approval {
    enum class Choice { Run, Ask, Refuse }

    /**
     * Tools a single "always allow" never covers, as on the server
     * (server/internal/runs/guard.go noBlanketAllow): they act on the
     * person's accounts, secrets or bots, and a rule would silently apply
     * to every bot, Slack runs and routines included.
     */
    val NO_BLANKET = setOf(
        "vault_type", "browser_open", "routine_create", "routine_update", "task_contract",
        "bot_create", "bot_update", "bot_archive",
    )

    /**
     * The phone's own calendar and contacts: once outside text is in the
     * run (a Slack message, a web page), reading them asks first, since the
     * answer can be posted back to Slack or carried off. web_fetch and card
     * pictures are limited to addresses already seen instead of asking.
     */
    val ASK_WHEN_TAINTED = setOf("calendar_list", "contact_search")

    fun canAlwaysAllow(tool: String) = tool !in NO_BLANKET

    fun decide(
        mode: String,
        rule: String?,
        touchesTheWorld: Boolean,
        tool: String = "",
        tainted: Boolean = false,
    ): Choice = when {
        rule == "block" -> Choice.Refuse
        rule == "allow" && canAlwaysAllow(tool) -> Choice.Run
        mode == "never" -> Choice.Run
        touchesTheWorld -> Choice.Ask
        tainted && tool in ASK_WHEN_TAINTED -> Choice.Ask
        else -> Choice.Run
    }
}

/**
 * Whether outside text has entered a run: a Slack message, a web page, an
 * integration's answer, or a message from a bot that had read such text.
 * As on the server, it only tightens approvals; it never loosens them.
 */
class RunTaint(initial: Boolean = false) {
    @Volatile var on: Boolean = initial
        private set

    fun mark() { on = true }

    companion object {
        /** Tools whose answer is outside text. */
        val SOURCES = setOf("web_search", "web_fetch", "browser_open", "browser_read", "calendar_list", "file_read", "recall")
    }
}
