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

/**
 * Keeps the live connection open while the app is closed, so alerts
 * arrive without Google's push service. Android requires the quiet
 * "connected" notification for this.
 */
class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as GbotApp
        // Go foreground first: a service started this way must, even to stop.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        runCatching { ServiceCompat.startForeground(this, Alerts.LIVE_ID, app.alerts.connection(), type) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        if (!app.alertsEnabled || app.mode == Mode.Server && app.http.server == null || !app.hasSession()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        app.live.start()
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as GbotApp
        if (!app.store.foreground && app.mode == Mode.Server) app.live.stop()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, LiveService::class.java)) }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
        }
    }
}
