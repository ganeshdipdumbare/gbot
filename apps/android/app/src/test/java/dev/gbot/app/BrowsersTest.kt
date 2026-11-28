package dev.gbot.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.local.Browsers
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Each bot browses in its own window on the phone; bots never share one. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BrowsersTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun eachBotHasItsOwnWindow() {
        val b = Browsers(ctx)
        val a = b.of("bot-a")
        assertSame(a, b.of("bot-a"))
        assertNotSame(a, b.of("bot-b"))
        assertNotSame(a.view(), b.of("bot-b").view())
    }

    @Test fun oneBotBrowsingDoesNotBlockAnother() {
        val b = Browsers(ctx)
        assertTrue(b.lock("bot-a").tryLock())
        assertTrue(b.lock("bot-b").tryLock())
        // The same bot's second run waits for its first.
        assertFalse(b.lock("bot-a").tryLock())
    }

    @Test fun closingABotGivesItAFreshWindowAndKeepsItsLock() {
        val b = Browsers(ctx)
        val first = b.of("bot-a")
        val lock = b.lock("bot-a")
        b.close("bot-a")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotSame(first, b.of("bot-a"))
        assertSame(lock, b.lock("bot-a"))
    }
}
