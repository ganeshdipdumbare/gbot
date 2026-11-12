package dev.gbot.app.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.gbot.app.MainActivity
import dev.gbot.app.R
import dev.gbot.app.data.ChatKey
import dev.gbot.app.data.Store
import dev.gbot.app.data.keyOf
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Message

/**
 * System notifications for what the web app notifies about: a bot that
 * needs you (with Approve and Decline right on an approval) and a bot that
 * has finished. Shown when the app isn't on that chat.
 */
class Alerts(private val context: Context, private val store: Store) {
    private val nm = NotificationManagerCompat.from(context)

    // The latest pending card per bot, so "needs you" can offer its buttons.
    private val cards = mutableMapOf<String, Message>()

    init {
        val sys = context.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(NotificationChannel(CH_ALERTS, "Bots that need you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Approvals, questions and requests to take over"
        })
        sys.createNotificationChannel(NotificationChannel(CH_DONE, "Finished work", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a bot finishes what you asked"
        })
        sys.createNotificationChannel(NotificationChannel(CH_LIVE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps gbot connected to your server so alerts arrive"
            setShowBadge(false)
        })
    }

    private fun showing(k: ChatKey) = store.foreground && store.openChat == k

    fun card(m: Message) {
        val botId = m.botId ?: return
        if (m.str("status") == "pending") cards[botId] = m else if (cards[botId]?.id == m.id) {
            cards.remove(botId)
            nm.cancel(idFor(keyOf(m)))
        }
    }

    fun needsYou(b: Bot, detail: String?) {
        if (showing(b.id)) return
        val card = cards[b.id]?.takeIf { it.str("status") == "pending" }
        val text = card?.content?.takeIf { it.isNotBlank() } ?: detail ?: "Open gbot to answer."
        val n = base(b.id, CH_ALERTS, "${b.name} needs you", text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (card != null && card.kind == "approval") {
            n.addAction(0, "Approve", action(card, "approve"))
            n.addAction(0, "Decline", action(card, "reject"))
        }
        post(b.id, n)
    }

    fun done(b: Bot) {
        if (showing(b.id)) return
        val last = store.now.bots.find { it.id == b.id }?.lastMessage?.content.orEmpty()
        post(b.id, base(b.id, CH_DONE, "${b.name} is done", plain(last).take(240)))
    }

    /** A finished reply in a group (groups have no "done" presence of their own). */
    fun reply(m: Message) {
        val gid = m.groupId ?: return
        val k = keyOf(m)
        if (showing(k)) return
        val g = store.now.groups.find { it.id == gid } ?: return
        val who = store.now.bots.find { it.id == m.botId }?.name ?: "A bot"
        post(k, base(k, CH_DONE, g.name, "$who: ${plain(m.content).take(240)}"))
    }

    fun clear(k: ChatKey) = nm.cancel(idFor(k))

    fun answered(cardId: String, k: ChatKey, label: String) {
        cards.entries.removeAll { it.value.id == cardId }
        nm.cancel(idFor(k))
        if (label.isNotEmpty()) android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun connection(): android.app.Notification =
        NotificationCompat.Builder(context, CH_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("gbot is connected")
            .setContentText("You'll get alerts when a bot needs you.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open(null))
            .build()

    private fun base(k: ChatKey, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF8B5CF6.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(k))

    private fun post(k: ChatKey, n: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.notify(idFor(k), n.build())
    }

    private fun open(k: ChatKey?): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (k != null) i.putExtra(MainActivity.EXTRA_CHAT, k)
        return PendingIntent.getActivity(context, (k ?: "").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(card: Message, decision: String): PendingIntent {
        val i = Intent(context, CardActionReceiver::class.java)
            .putExtra(CardActionReceiver.EXTRA_CARD, card.id)
            .putExtra(CardActionReceiver.EXTRA_CHAT, keyOf(card))
            .putExtra(CardActionReceiver.EXTRA_DECISION, decision)
        return PendingIntent.getBroadcast(context, (card.id + decision).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CH_ALERTS = "alerts"
        const val CH_DONE = "done"
        const val CH_LIVE = "live"
        const val LIVE_ID = 1
        fun idFor(k: ChatKey) = 1000 + (k.hashCode() and 0x7ffffff)
        fun plain(md: String) = md.replace(Regex("```[\\s\\S]*?```"), " (code) ").replace(Regex("[*_`#>|]"), "").replace(Regex("\\s+"), " ").trim()
    }
}
