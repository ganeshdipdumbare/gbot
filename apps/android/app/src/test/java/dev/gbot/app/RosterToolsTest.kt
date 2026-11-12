package dev.gbot.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.local.BrowserController
import dev.gbot.app.local.LocalDb
import dev.gbot.app.local.LocalTools
import dev.gbot.app.local.MAIN_BOT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RosterToolsTest {
    private fun tools(botId: String): List<String> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return LocalTools(ctx, LocalDb(ctx), BrowserController(ctx), botId).schemas.map { it.name }
    }

    @Test
    fun `the main bot can manage the roster`() {
        val names = tools(MAIN_BOT)
        listOf("bot_list", "bot_create", "bot_update", "bot_archive").forEach {
            assertTrue("$it missing from $names", it in names)
        }
    }

    @Test
    fun `other bots cannot manage the roster`() {
        assertFalse(tools("b_someone_else").any { it.startsWith("bot_") })
    }
}
