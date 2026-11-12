package dev.gbot.app.local

/** How long the phone keeps a model loaded after its last reply. */
object ModelMemory {
    /** Never unload until the model or mode changes. */
    const val FOREVER = 0

    fun minutesOf(raw: String?): Int {
        val n = raw?.toIntOrNull() ?: return 2
        return when {
            n == 0 -> 0
            n in 1..600 -> n
            else -> 2
        }
    }

    fun label(minutes: Int): String =
        if (minutes == FOREVER) "Keep until I switch" else "Unload after $minutes min"
}
