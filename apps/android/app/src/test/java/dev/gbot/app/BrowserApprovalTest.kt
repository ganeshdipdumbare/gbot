package dev.gbot.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.local.BrowserController
import dev.gbot.app.local.LocalDb
import dev.gbot.app.local.LocalTools
import dev.gbot.app.local.canonicalArgs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the person is asked to approve for a browser step is what it does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BrowserApprovalTest {
    private val tools by lazy {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalTools(ctx, LocalDb(ctx), BrowserController(ctx), "b_test")
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun `a step with no element is refused before anyone is asked`() {
        // The server's browser_press {key} arrives as browser_enter with no id.
        assertNotNull(tools.precheck("browser_enter", canonicalArgs("browser_enter", obj("""{"key":"Enter"}"""))))
        assertNotNull(tools.precheck("browser_click", obj("{}")))
        assertNull(tools.precheck("browser_click", canonicalArgs("browser_click", obj("""{"ref":4}"""))))
        assertNull(tools.precheck("browser_open", obj("""{"url":"https://example.com"}""")))
    }

    @Test
    fun `a fill that submits says so on its card`() {
        val args = canonicalArgs("browser_fill", obj("""{"ref":3,"text":"hi","submit":true}"""))
        assertTrue(tools.describe("browser_fill", args).endsWith(", then submit the form"))
        assertEquals("Fill in “#3” with hi", tools.describe("browser_fill", canonicalArgs("browser_fill", obj("""{"ref":3,"text":"hi"}"""))))
    }

    @Test
    fun `a search's query is what the card offers to open`() {
        val args = canonicalArgs("browser_open", obj("""{"query":"cheap flights"}"""))
        assertEquals("Open cheap flights", tools.describe("browser_open", args))
    }
}
