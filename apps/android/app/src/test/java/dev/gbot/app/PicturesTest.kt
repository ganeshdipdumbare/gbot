package dev.gbot.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.local.Mcp
import dev.gbot.app.local.kindOf
import dev.gbot.app.local.showableImage
import dev.gbot.app.local.sizeOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What an integration hands back: the model reads text, and a picture is
 * taken out and kept for the chat instead of being lost.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PicturesTest {
    private fun blocks(raw: String) = Json.parseToJsonElement(raw) as JsonArray

    private val png = Base64.encodeToString(ByteArray(64) { it.toByte() }, Base64.NO_WRAP)

    @Test
    fun `a picture is kept and named in the text`() {
        val mcp = Mcp(RuntimeEnvironment.getApplication())
        var kept: Pair<Int, String>? = null
        val out = mcp.readContent(
            "Excalidraw",
            blocks("""[{"type":"text","text":"Here is the diagram."},{"type":"image","data":"$png","mimeType":"image/png"}]"""),
        ) { bytes, mime -> kept = bytes.size to mime; "[a picture is in the chat]" }
        assertEquals(64 to "image/png", kept)
        assertEquals("Here is the diagram.\n[a picture is in the chat]", out)
    }

    @Test
    fun `a picture inside a resource counts too, and a file is only named`() {
        val mcp = Mcp(RuntimeEnvironment.getApplication())
        var kept = 0
        val out = mcp.readContent(
            "Drive",
            blocks(
                """[{"type":"resource","resource":{"uri":"file://a.png","mimeType":"image/png","blob":"$png"}},""" +
                    """{"type":"resource","resource":{"uri":"file://b.pdf","mimeType":"application/pdf","blob":"$png"}}]""",
            ),
        ) { _, _ -> kept++; "[kept]" }
        assertEquals(1, kept)
        assertTrue(out, out.contains("[kept]"))
        assertTrue(out, out.contains("PDF file, which the chat can't show"))
    }

    @Test
    fun `with nowhere to keep it, the text still says a picture came`() {
        val mcp = Mcp(RuntimeEnvironment.getApplication())
        val out = mcp.readContent("Draw", blocks("""[{"type":"image","data":"$png","mimeType":"image/png"}]"""), null)
        assertEquals("[Draw sent a picture.]", out)
    }

    @Test
    fun `an SVG is named, never kept`() {
        val mcp = Mcp(RuntimeEnvironment.getApplication())
        var kept = 0
        val out = mcp.readContent(
            "Draw",
            blocks("""[{"type":"image","data":"$png","mimeType":"image/svg+xml"}]"""),
        ) { _, _ -> kept++; "[kept]" }
        assertEquals(0, kept)
        assertTrue(out, out.contains("SVG file, which the chat can't show"))
        assertTrue(showableImage("image/png"))
        assertTrue(showableImage("IMAGE/JPEG"))
    }

    @Test
    fun `sizes and types read as a person would say them`() {
        assertEquals("PNG", kindOf("image/png"))
        assertEquals("SVG", kindOf("image/svg+xml"))
        assertEquals("PDF", kindOf("application/pdf"))
        assertEquals("file", kindOf(""))
        assertEquals("512 bytes", sizeOf(512))
        assertEquals("2 KB", sizeOf(2048))
        assertEquals("1.5 MB", sizeOf((1.5 * (1 shl 20)).toInt()))
    }
}
