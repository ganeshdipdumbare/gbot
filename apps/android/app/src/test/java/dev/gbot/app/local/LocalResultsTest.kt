package dev.gbot.app.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class LocalResultsTest {
    // No picture URLs in these tests, so nothing is fetched over the network.
    private val results = LocalResults(File(System.getProperty("java.io.tmpdir"), "gbot-results-test"))

    private fun args(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun itemsBecomeCards() {
        val set = results.build(args("""{"title":"Hotels","items":[
            {"title":"Echo Hotel","subtitle":"The Mall","link":"https://e.com/x","fields":[{"label":"Rating","value":"9.6"}]},
            {"title":"Longitude"}]}"""))
        assertNotNull(set)
        assertEquals("Hotels", set!!.title)
        val first = set.items[0].jsonObject
        assertEquals("Echo Hotel", first["title"]!!.jsonPrimitive.content)
        assertEquals("The Mall", first["subtitle"]!!.jsonPrimitive.content)
        assertEquals("https://e.com/x", first["link"]!!.jsonPrimitive.content)
        val field = first["fields"]!!.jsonArray[0].jsonObject
        assertEquals("Rating", field["label"]!!.jsonPrimitive.content)
        assertEquals("9.6", field["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun unsafeLinkIsDropped() {
        val set = results.build(args("""{"items":[{"title":"A","link":"javascript:alert(1)"},{"title":"B"}]}"""))
        assertNotNull(set)
        assertFalse(set!!.items[0].jsonObject.containsKey("link"))
    }

    @Test
    fun itemWithoutTitleIsSkipped() {
        val set = results.build(args("""{"items":[{"subtitle":"no name"},{"title":"A"},{"title":"B"}]}"""))!!
        assertEquals(2, set.items.size)
    }

    @Test
    fun fieldsNeedBothLabelAndValue() {
        val set = results.build(args("""{"items":[{"title":"A","fields":[{"label":"","value":"x"},{"label":"Price"},{"label":"Dates","value":"9-13 Dec"}]},{"title":"B"}]}"""))!!
        val fields = set.items[0].jsonObject["fields"]!!.jsonArray
        assertEquals(1, fields.size)
        assertEquals("Dates", fields[0].jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun listIsCappedAtTwelve() {
        val items = (1..20).joinToString(",") { """{"title":"Item $it"}""" }
        val set = results.build(args("""{"items":[$items]}"""))!!
        assertEquals(LocalResults.MAX_CARDS, set.items.size)
    }

    @Test
    fun numbersKeepPlainDigits() {
        // A model may send a bare number where the schema asks for text.
        val set = results.build(args("""{"items":[{"title":"A","fields":[{"label":"n","value":1e20}]},{"title":"B"}]}"""))!!
        val field = set.items[0].jsonObject["fields"]!!.jsonArray[0].jsonObject
        assertEquals("100000000000000000000", field["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun onlyPublicAddressesAreFetched() {
        assertFalse(LocalResults.isPublic(InetAddress.getByName("127.0.0.1")))
        assertFalse(LocalResults.isPublic(InetAddress.getByName("10.0.0.5")))
        assertFalse(LocalResults.isPublic(InetAddress.getByName("169.254.1.1")))
        assertFalse(LocalResults.isPublic(InetAddress.getByName("fd00::1")))
        assertTrue(LocalResults.isPublic(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun privateImageLiteralIsNotFetched() {
        // Rejected before any connection is made, so no network is used.
        val set = results.build(args("""{"items":[{"title":"A","image":"https://192.168.1.1/x.png"},{"title":"B"}]}"""))!!
        assertFalse(set.items[0].jsonObject.containsKey("file"))
    }

    @Test
    fun oneItemIsNotCards() {
        assertNull(results.build(args("""{"items":[{"title":"A"}]}""")))
    }

    @Test
    fun onlyHttpsCountsAsLink() {
        assertTrue(LocalResults.isHttps("https://example.com/a"))
        assertFalse(LocalResults.isHttps("http://example.com/a"))
        assertFalse(LocalResults.isHttps("data:text/html,x"))
    }
}
