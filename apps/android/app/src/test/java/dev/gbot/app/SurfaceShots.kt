package dev.gbot.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.local.Surfaces
import dev.gbot.app.net.Message
import dev.gbot.app.ui.GbotTheme
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.chat.SurfaceRow
import dev.gbot.app.ui.chat.SurfaceView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * show_ui surfaces drawn by the real A2UI renderer from our catalog: what
 * the person sees, and what a tap sends back. PNGs go to build/shots.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class SurfaceShots {
    @get:Rule val rule = createComposeRule()

    private val form = Surfaces.build(Json.parseToJsonElement("""{"title":"Book a table","data":{"name":"","size":["2"],"window":true},"components":[
        {"id":"root","component":"Card","child":"col"},
        {"id":"col","component":"Column","children":["h","sub","name","size","window","d","row"]},
        {"id":"h","component":"Text","text":"Dinner at Bacalhau","variant":"h3"},
        {"id":"sub","component":"Text","text":"Friday 20:00 · Rua das Flores 12","variant":"caption"},
        {"id":"name","component":"TextField","label":"Name on the booking","value":{"path":"/name"}},
        {"id":"size","component":"ChoicePicker","label":"People","options":[{"label":"2","value":"2"},{"label":"4","value":"4"},{"label":"6","value":"6"}],"value":{"path":"/size"},"variant":"mutuallyExclusive","displayStyle":"chips"},
        {"id":"window","component":"CheckBox","label":"Window table if possible","value":{"path":"/window"}},
        {"id":"d","component":"Divider"},
        {"id":"row","component":"Row","children":["no","go"],"justify":"end"},
        {"id":"nol","component":"Text","text":"Not now"},
        {"id":"no","component":"Button","child":"nol","action":{"event":{"name":"cancel"}}},
        {"id":"gol","component":"Text","text":"Book it"},
        {"id":"go","component":"Button","child":"gol","variant":"primary","action":{"event":{"name":"book","context":{"name":{"path":"/name"},"size":{"path":"/size"},"window":{"path":"/window"}}}}}]}""").jsonObject) { null }

    private fun save(name: String) {
        repeat(6) { rule.waitForIdle(); Thread.sleep(100) }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/shots").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) = GbotTheme {
        Box(Modifier.fillMaxWidth().background(LocalG.current.bg).padding(16.dp)) { content() }
    }

    @Test
    fun aTapSendsTheFormBack() {
        var got: Pair<String, JsonObject>? = null
        rule.setContent { Page { SurfaceView("s1", form.spec, live = true, onEvent = { n, c -> got = n to c }) } }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("Ana")
        rule.onNodeWithText("4").performClick()
        save("surface-form")
        rule.onNodeWithText("Book it").performClick()
        rule.waitForIdle()
        val (name, context) = got ?: error("no event")
        assertEquals("book", name)
        assertEquals("Ana", context["name"]!!.jsonPrimitive.content)
        assertEquals("[\"4\"]", context["size"].toString())
        assertEquals("true", context["window"].toString())
    }

    @Test
    fun anAnsweredSurfaceNoLongerAnswers() {
        var got = 0
        rule.setContent { Page { SurfaceView("s2", form.spec, live = false, onEvent = { _, _ -> got++ }) } }
        rule.waitForIdle()
        rule.onNodeWithText("Book it").performClick()
        rule.waitForIdle()
        assertEquals(0, got)
    }

    @Test
    fun aListOfThingsToCompare() {
        val m = Message(
            id = "m1", botId = "b1", role = "event", content = "Hotels in Lisbon", createdAt = "2026-11-22T10:00:00Z",
            data = Json.parseToJsonElement("""{"kind":"results","title":"Hotels in Lisbon","items":[
                {"title":"Memmo Alfama","subtitle":"Alfama · 0.8 km from centre","link":"https://example.com/memmo","fields":[{"label":"Price","value":"€212 / night"},{"label":"Rating","value":"9.1"}]},
                {"title":"The Lumiares","subtitle":"Bairro Alto","link":"https://example.com/lumiares","fields":[{"label":"Price","value":"€189 / night"},{"label":"Rating","value":"9.3"}]},
                {"title":"Casa do Barão","subtitle":"Chiado","fields":[{"label":"Price","value":"€145 / night"}]}]}""").jsonObject,
        )
        rule.setContent { Page { SurfaceRow(m, null) } }
        save("surface-results")
        rule.onNodeWithText("Memmo Alfama").assertExists()
        rule.onNodeWithText("€189 / night").assertExists()
    }

    @Test
    fun aWaitingCardInTheChat() {
        val m = Message(
            id = "c1", botId = "b1", role = "card", content = "Book a table", createdAt = "2026-11-22T10:00:00Z",
            data = buildJsonObject { put("kind", "ui"); put("status", "pending"); put("title", "Book a table"); put("surface", form.spec) },
        )
        rule.setContent { Page { SurfaceRow(m, null) } }
        save("surface-card")
        rule.onNodeWithText("Skip").assertExists()
        assertTrue(form.interactive)
    }
}
