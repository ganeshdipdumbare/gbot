package dev.gbot.app.live

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone mode, while the app is closed: holds the Slack connection open,
 * and keeps a routine or other work alive until it finishes. Android
 * requires the quiet notification for it. It stops once there is no
 * Slack workspace to listen on and nothing left to run.
 */
class LocalService : Service() {
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as GbotApp
        val slack = app.slackConnected()
        val type = when {
            Build.VERSION.SDK_INT < 34 -> 0
            slack -> ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        val note = if (slack) app.alerts.listening() else app.alerts.working()
        runCatching { ServiceCompat.startForeground(this, Alerts.LOCAL_ID, note, type) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        if (app.mode != Mode.Local || (!slack && !app.localBusy() && intent?.getBooleanExtra(EXTRA_WORK, false) != true)) {
            finish()
            return START_NOT_STICKY
        }
        if (slack) app.startLocalListening()
        // Let go once Slack is gone and the work is done.
        watch?.cancel()
        watch = app.scope.launch {
            delay(20_000)
            while (app.slackConnected() || app.localBusy()) delay(15_000)
            finish()
        }
        return if (slack) START_STICKY else START_NOT_STICKY
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watch?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_WORK = "work"

        /** Run while phone mode has a Slack workspace to listen on; stop otherwise. */
        fun sync(context: Context) {
            val app = context.applicationContext as GbotApp
            if (app.mode == Mode.Local && app.slackConnected()) {
                runCatching { context.startForegroundService(Intent(context, LocalService::class.java)) }
            } else if (!app.localBusy()) {
                stop(context)
            }
        }

        /** Keep the app alive for a routine that fires while it is closed. */
        fun work(context: Context) {
            runCatching { context.startForegroundService(Intent(context, LocalService::class.java).putExtra(EXTRA_WORK, true)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalService::class.java))
        }
    }
}
