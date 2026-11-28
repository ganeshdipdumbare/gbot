package dev.gbot.app.local

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Every bot's own browser window on this phone. A bot gets its own WebView
 * (page, history and screen) the first time it browses, so what one bot
 * opens never shows up on another's computer. Its lock keeps two runs of
 * the same bot (its chat and a group, say) from driving it at once.
 */
class Browsers(private val ctx: Context) {
    private val windows = ConcurrentHashMap<String, BrowserController>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun of(botId: String): BrowserController = windows.getOrPut(botId) { BrowserController(ctx) }

    fun lock(botId: String): Mutex = locks.getOrPut(botId) { Mutex() }

    /** A bot that is gone lets go of its window. */
    fun close(botId: String) {
        // Marked closed right away, destroyed on the main thread. The lock
        // stays: a run still holding it unlocks the same one.
        windows.remove(botId)?.close()
    }

    fun closeAll() {
        windows.keys.toList().forEach(::close)
    }
}
