package dev.gbot.app.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.net.Routine
import java.time.Instant

/**
 * Phone mode: wakes gbot for the next routine, even when it is closed.
 * Waking the app is enough: phone mode starts its routines, Slack and
 * unfinished work as the app starts, and a due routine fires at once.
 * Exact when Android allows it, otherwise within a few minutes.
 */
object RoutineAlarm {
    fun arm(context: Context, routines: List<Routine>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, RoutineAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = routines.filter { it.active && it.kind == "schedule" }
            .mapNotNull { r -> r.nextRunAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } }
            .minOrNull()
        if (next == null) { am.cancel(pi); return }
        val at = maxOf(next, System.currentTimeMillis() + 1_000)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Whether routines can fire on time; otherwise Android may delay them a little. */
    fun exact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
}

class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GbotApp
        if (app.mode != Mode.Local) return
        // Keep the app up while the routine runs.
        LocalService.work(context)
        app.tickRoutines()
    }
}
