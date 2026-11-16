package dev.gbot.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.data.Store
import dev.gbot.app.data.groupKey
import dev.gbot.app.live.Alerts
import dev.gbot.app.live.Events
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Group
import dev.gbot.app.net.Http
import dev.gbot.app.net.Message
import dev.gbot.app.net.Presence
import dev.gbot.app.net.RemoteApi
import dev.gbot.app.net.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What reaches the phone's notification shade. The events are the ones both
 * brains send — the server's stream and the on-device run — so this covers
 * phone mode and server mode at once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AlertsTest {
    private lateinit var store: Store
    private lateinit var alerts: Alerts
    private lateinit var events: Events

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val shade get() = shadowOf(nm).allNotifications

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store = Store(RemoteApi(Http(app)), app)
        alerts = Alerts(app, store)
        events = Events(store, alerts)
        store.upsertBot(Bot(id = "b1", name = "Joker"))
        store.foreground = false
    }

    private fun event(type: String, botId: String? = null, groupId: String? = null, data: JsonObject? = null) =
        events.handle(buildJsonObject {
            put("type", type)
            if (botId != null) put("botId", botId)
            if (groupId != null) put("groupId", groupId)
            if (data != null) put("data", data)
        })

    private fun presence(state: String, detail: String? = null) =
        events.handle(buildJsonObject {
            put("type", "presence")
            put("botId", "b1")
            put("data", json.encodeToJsonElement(Presence(state = state, detail = detail)))
        })

    private fun message(m: Message, type: String = "message") =
        events.handle(buildJsonObject {
            put("type", type)
            put("botId", m.botId ?: "")
            m.groupId?.let { put("groupId", it) }
            put("data", json.encodeToJsonElement(m))
        })

    @Test fun `a bot that finishes says so`() {
        message(Message(id = "m1", botId = "b1", role = "bot", content = "Booked the table for 8pm."), "message_done")
        presence("working")
        presence("done")
        assertEquals(1, shade.size)
        assertEquals("Joker is done", shade[0].extras.getString("android.title"))
        assertEquals("Booked the table for 8pm.", shade[0].extras.getString("android.text"))
        assertEquals(Alerts.CH_DONE, shade[0].channelId)
    }

    @Test fun `done twice in a row only alerts once`() {
        presence("working")
        presence("done")
        presence("done")
        assertEquals(1, shade.size)
    }

    @Test fun `nothing is said about the chat you are looking at`() {
        store.foreground = true
        store.openChat = "b1"
        presence("working")
        presence("done")
        presence("waiting", "Needs you")
        assertTrue(shade.isEmpty())
    }

    @Test fun `an approval can be answered from the notification`() {
        message(
            Message(
                id = "c1", botId = "b1", role = "card", content = "Send the email to Sam?",
                data = buildJsonObject { put("kind", "approval"); put("status", "pending") },
            ),
        )
        presence("waiting", "Needs you")
        assertEquals(1, shade.size)
        val n = shade[0]
        assertEquals("Joker needs you", n.extras.getString("android.title"))
        assertEquals("Send the email to Sam?", n.extras.getString("android.text"))
        assertEquals(Alerts.CH_ALERTS, n.channelId)
        assertEquals(listOf("Approve", "Decline"), n.actions.map { it.title.toString() })
    }

    @Test fun `an answered card takes its notification with it`() {
        message(
            Message(
                id = "c1", botId = "b1", role = "card", content = "Send the email to Sam?",
                data = buildJsonObject { put("kind", "approval"); put("status", "pending") },
            ),
        )
        presence("waiting", "Needs you")
        assertEquals(1, shade.size)
        message(
            Message(
                id = "c1", botId = "b1", role = "card", content = "Send the email to Sam?",
                data = buildJsonObject { put("kind", "approval"); put("status", "approved") },
            ),
            "message_update",
        )
        assertTrue(shade.isEmpty())
    }

    @Test fun `a reply in a group names who said it`() {
        store.upsertGroup(Group(id = "g1", name = "Trip", botIds = listOf("b1")))
        message(Message(id = "m2", botId = "b1", groupId = "g1", role = "bot", content = "Flights are booked."), "message_done")
        assertEquals(1, shade.size)
        assertEquals("Trip", shade[0].extras.getString("android.title"))
        assertEquals("Joker: Flights are booked.", shade[0].extras.getString("android.text"))
    }

    @Test fun `opening the chat clears its notification`() {
        presence("working")
        presence("done")
        assertEquals(1, shade.size)
        alerts.clear("b1")
        assertTrue(shade.isEmpty())
    }

    @Test fun `without permission nothing is posted and nothing crashes`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        presence("working")
        presence("done")
        presence("waiting", "Needs you")
        assertTrue(shade.isEmpty())
    }

    @Test fun `the channels a person can turn off one by one exist`() {
        val ids = nm.notificationChannels.map { it.id }
        assertTrue(ids.containsAll(listOf(Alerts.CH_ALERTS, Alerts.CH_DONE, Alerts.CH_LIVE)))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Alerts.CH_ALERTS).importance)
    }

    @Test fun `a group reply is not confused with its own chat`() {
        store.upsertGroup(Group(id = "g1", name = "Trip", botIds = listOf("b1")))
        store.foreground = true
        store.openChat = groupKey("g1")
        message(Message(id = "m3", botId = "b1", groupId = "g1", role = "bot", content = "Flights are booked."), "message_done")
        assertTrue(shade.isEmpty())
    }
}
