package dev.gbot.app.local

/** Whether a tool step runs, asks, or is refused. Kept pure so it can be tested. */
object Approval {
    enum class Choice { Run, Ask, Refuse }

    fun decide(mode: String, rule: String?, touchesTheWorld: Boolean): Choice = when {
        rule == "block" -> Choice.Refuse
        rule == "allow" -> Choice.Run
        mode == "never" -> Choice.Run
        !touchesTheWorld -> Choice.Run
        mode == "ask" -> Choice.Ask
        else -> Choice.Ask
    }
}
