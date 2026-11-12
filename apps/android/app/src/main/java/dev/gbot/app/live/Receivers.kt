package dev.gbot.app.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.gbot.app.GbotApp
import kotlinx.coroutines.launch

/** Starts background alerts again after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GbotApp
        if (app.alertsEnabled && app.http.server != null && app.hasSession()) LiveService.start(context)
    }
}

/** Approve or Decline straight from a notification. */
class CardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GbotApp
        val card = intent.getStringExtra(EXTRA_CARD) ?: return
        val chat = intent.getStringExtra(EXTRA_CHAT) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                val m = app.api.answer(card, decision)
                app.store.patchMessage(chat, card) { m }
                app.alerts.answered(card, chat, if (decision == "approve") "Approved" else "Declined")
            } catch (e: Exception) {
                app.alerts.answered(card, "", "")
                android.widget.Toast.makeText(context, e.message ?: "Couldn't answer", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CARD = "card"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_DECISION = "decision"
    }
}
