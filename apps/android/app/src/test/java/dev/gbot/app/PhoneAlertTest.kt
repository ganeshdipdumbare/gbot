package dev.gbot.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.data.Store
import dev.gbot.app.live.Alerts
import dev.gbot.app.live.Events
import dev.gbot.app.local.LocalApi
import dev.gbot.app.local.LocalDb
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Http
import dev.gbot.app.net.RemoteApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Phone mode, end to end: a real run on the phone's own brain has to reach
 * the notification shade when the person is not watching that chat. It used
 * to go straight from working to idle, so nothing was ever posted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhoneAlertTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `a run that finishes with the app away posts a notification`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Store(RemoteApi(Http(app)), app)
        val alerts = Alerts(app, store)
        val events = Events(store, alerts)
        val db = LocalDb(app)
        val local = LocalApi(app, db, events, scope)
        store.use(local)
        val bot = Bot(id = "b1", name = "Joker")
        db.putBot(bot)
        store.upsertBot(bot)
        // The person has left the app, or is anywhere but this chat.
        store.foreground = false

        // No model and no provider: the run still ends with something the
        // person needs to read, which is exactly what has to be announced.
        local.brain.launch(bot, "book a table")

        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = waitFor { shadowOf(nm).allNotifications.isNotEmpty() }
        assertTrue("nothing reached the shade", posted)
        val n = shadowOf(nm).allNotifications.first()
        assertEquals("Joker is done", n.extras.getString("android.title"))
        assertEquals(Alerts.CH_DONE, n.channelId)
        assertTrue(n.extras.getString("android.text").orEmpty().isNotBlank())

        // And the run was kept alive by a foreground service, so leaving the
        // app cannot kill it before it gets here.
        val started = shadowOf(app).nextStartedService
        assertEquals("dev.gbot.app.live.LocalService", started?.component?.className)
        scope.cancel()
    }

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return false
    }
}
