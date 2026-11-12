package dev.gbot.app.local

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.gbot.app.net.Routine

/** The server's @mention rule, so groups pick the same bots. */
object Mentions {
    fun matches(text: String, name: String): Boolean {
        val t = text.lowercase()
        val at = "@" + name.lowercase()
        var i = t.indexOf(at)
        while (i >= 0) {
            val before = t.getOrNull(i - 1)
            val after = t.getOrNull(i + at.length)
            fun word(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '-')
            if (!word(before) && !word(after)) return true
            i = t.indexOf(at, i + 1)
        }
        return false
    }
}

/**
 * Runs scheduled routines while gbot is open. A phone has no always-on
 * scheduler like the server, so a missed time catches up on the next tick.
 */
class LocalRoutines(
    private val db: LocalDb,
    private val scope: CoroutineScope,
    /** After each tick: set the alarm for the next one, so it fires with the app closed too. */
    private val arm: (List<Routine>) -> Unit = {},
    private val fire: (Routine) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { tick() }
                delay(20_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Fire what is due now, e.g. when the alarm wakes the app. */
    fun tickNow() = scope.launch(Dispatchers.IO) { runCatching { tick() } }

    @Synchronized private fun tick() {
        val now = ZonedDateTime.now()
        for (r in db.allRoutines()) {
            if (!r.active || r.kind != "schedule") continue
            val cron = Cron.parse(r.schedule.orEmpty()) ?: continue
            val due = r.nextRunAt?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()) }.getOrNull() }
            if (due == null) {
                db.putRoutine(r.copy(nextRunAt = cron.next(now)?.toInstant()?.toString()))
                continue
            }
            if (due.isAfter(now)) continue
            // Move the clock before firing, so a slow run can't fire twice.
            db.putRoutine(r.copy(nextRunAt = cron.next(now)?.toInstant()?.toString(), lastRunAt = now.toInstant().toString()))
            fire(r)
        }
        arm(db.allRoutines())
    }
}
