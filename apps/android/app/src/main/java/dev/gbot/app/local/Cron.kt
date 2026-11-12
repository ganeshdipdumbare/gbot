package dev.gbot.app.local

import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * Five-field cron (minute hour day month weekday), plus the same presets the
 * server accepts. Ported from server/internal/cron so a routine written on
 * the phone means the same thing on a server and back.
 */
class Cron private constructor(private val fields: List<Long>) {
    private val min = fields[0]
    private val hour = fields[1]
    private val dom = fields[2]
    private val month = fields[3]
    private val dow = fields[4]

    fun matches(t: LocalDateTime): Boolean {
        val d = t.dayOfWeek.value % 7 // Monday=1 … Sunday=0
        return bit(min, t.minute) && bit(hour, t.hour) && bit(dom, t.dayOfMonth) &&
            bit(month, t.monthValue) && bit(dow, d)
    }

    /** The first matching minute after [after], or null within a year. */
    fun next(after: ZonedDateTime): ZonedDateTime? {
        var t = after.plusMinutes(1).withSecond(0).withNano(0)
        repeat(366 * 24 * 60) {
            if (matches(t.toLocalDateTime())) return t
            t = t.plusMinutes(1)
        }
        return null
    }

    private fun bit(bits: Long, v: Int) = v in 0..63 && (bits shr v) and 1L == 1L

    companion object {
        private val presets = mapOf(
            "@hourly" to "0 * * * *",
            "@daily" to "0 9 * * *",
            "@weekly" to "0 9 * * 1",
            "@monthly" to "0 9 1 * *",
        )

        /** A short human line for the routine list. */
        fun describe(expr: String): String {
            val e = presets[expr.trim()] ?: expr.trim()
            val f = e.split(Regex("\\s+"))
            if (f.size != 5) return expr
            val at = "${f[1].padStart(2, '0')}:${f[0].padStart(2, '0')}"
            val days = mapOf("0" to "Sunday", "1" to "Monday", "2" to "Tuesday", "3" to "Wednesday", "4" to "Thursday", "5" to "Friday", "6" to "Saturday")
            return when {
                f[2] == "*" && f[3] == "*" && f[4] == "*" -> "Every day at $at"
                f[2] == "*" && f[3] == "*" && days.containsKey(f[4]) -> "Every ${days[f[4]]} at $at"
                f[2] == "1" && f[3] == "*" && f[4] == "*" -> "Every month on the 1st at $at"
                else -> e
            }
        }

        fun parse(expr: String): Cron? {
            val e = presets[expr.trim()] ?: expr.trim()
            val parts = e.split(Regex("\\s+"))
            if (parts.size != 5) return null
            val ranges = listOf(0 to 59, 0 to 23, 1 to 31, 1 to 12, 0 to 7)
            val out = parts.mapIndexed { i, p -> field(p, ranges[i].first, ranges[i].second) ?: return null }.toMutableList()
            if ((out[4] shr 7) and 1L == 1L) out[4] = out[4] or 1L
            return Cron(out)
        }

        private fun field(s: String, lo: Int, hi: Int): Long? {
            var bits = 0L
            for (part0 in s.split(",")) {
                var part = part0
                var step = 1
                val slash = part.indexOf('/')
                if (slash >= 0) {
                    step = part.substring(slash + 1).toIntOrNull() ?: return null
                    if (step < 1) return null
                    part = part.substring(0, slash)
                }
                var a = lo
                var b = hi
                if (part != "*") {
                    val dash = part.indexOf('-')
                    if (dash >= 0) {
                        a = part.substring(0, dash).toIntOrNull() ?: return null
                        b = part.substring(dash + 1).toIntOrNull() ?: return null
                    } else {
                        a = part.toIntOrNull() ?: return null
                        b = a
                    }
                }
                if (a < lo || b > hi || a > b) return null
                var v = a
                while (v <= b) {
                    bits = bits or (1L shl v)
                    v += step
                }
            }
            return bits
        }
    }
}
