package dev.gbot.app.local

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

    @Test
    fun listAnswerBecomesCards() {
        val set = results.build("""[{"name":"Echo Hotel","location":"The Mall","rating":9.6,"url":"https://e.com/x"},{"name":"Longitude","rating":8.8}]""")
        assertNotNull(set)
        val first = set!!.items[0].jsonObject
        assertEquals("Echo Hotel", first["title"]!!.jsonPrimitive.content)
        assertEquals("The Mall", first["subtitle"]!!.jsonPrimitive.content)
        assertEquals("https://e.com/x", first["link"]!!.jsonPrimitive.content)
        val field = first["fields"]!!.jsonArray[0].jsonObject
        assertEquals("Rating", field["label"]!!.jsonPrimitive.content)
        assertEquals("9.6", field["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun unsafeLinkIsDropped() {
        val set = results.build("""[{"name":"A","url":"javascript:alert(1)"},{"name":"B"}]""")
        assertNotNull(set)
        assertFalse(set!!.items[0].jsonObject.containsKey("link"))
    }

    @Test
    fun wrappedListFindsRecords() {
        val set = results.build("""{"title":"Hotels","results":[{"name":"A"},{"name":"B"}]}""")
        assertEquals("Hotels", set!!.title)
        assertEquals(2, set.items.size)
    }

    @Test
    fun largeNumbersKeepTheirDigits() {
        val set = results.build("""[{"name":"A","n":1e20},{"name":"B"}]""")!!
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
    fun singleRecordIsNotCards() {
        assertNull(results.build("""[{"name":"A"}]"""))
    }

    @Test
    fun plainTextIsNotCards() {
        assertNull(results.build("Booked 2 nights at the Mall."))
        assertTrue(results.build("not json").let { it == null })
    }
}
