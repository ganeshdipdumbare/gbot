package dev.gbot.app.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tool arguments keep their shape on the way from the model to a tool. */
class ToolArgsTest {
    private val results = LocalResults(File(System.getProperty("java.io.tmpdir"), "gbot-toolargs-test"))

    private fun toJson(m: Map<String, Any?>) = JsonObject(m.mapValues { (_, v) -> LocalBrain.jsonOf(v) })

    @Test
    fun providerArgumentsPassThroughUnchanged() {
        // A provider's call arrives as JSON already; it used to be turned
        // into text, so items became a string and strings gained quotes.
        val call = Json.parseToJsonElement(
            """{"title":"Hotels","items":[{"title":"Hotel Paradiso","fields":[{"label":"4 nights","value":"₹5,783"}]},{"title":"The Grand"}],"n":7}""",
        ).jsonObject
        val args = toJson(call)
        assertEquals(call, args)
        assertTrue(args["items"] is JsonArray)
        assertNotNull(results.build(args))
    }

    @Test
    fun onDeviceListsAndMapsBecomeJson() {
        val args = toJson(mapOf("title" to "Hotels", "items" to listOf(mapOf("title" to "A"), mapOf("title" to "B", "n" to 2))))
        assertTrue(args["items"] is JsonArray)
        assertEquals(2, results.build(args)!!.items.size)
    }

    @Test
    fun itemsSentAsTextStillBecomeCards() {
        val args = JsonObject(mapOf("items" to JsonPrimitive("""[{"title":"A"},{"title":"B"}]""")))
        assertEquals(2, results.build(args)!!.items.size)
    }

    @Test
    fun listWhereTextIsExpectedReadsAsItsJson() {
        assertEquals("[\"a\",\"b\"]", argText(Json.parseToJsonElement("""["a","b"]""")))
        assertEquals("a", argText(JsonPrimitive("a")))
        assertEquals("", argText(null))
        assertEquals(listOf("a", "b"), choiceLines(argText(Json.parseToJsonElement("""["a","b"]""")), 4))
    }

    @Test
    fun webmailIsRecognised() {
        assertTrue(LocalTools.isWebmail("https://mail.google.com/mail/u/0/#inbox"))
        assertTrue(LocalTools.isWebmail("gmail.com"))
        assertTrue(LocalTools.isWebmail("https://outlook.live.com/mail/"))
        assertFalse(LocalTools.isWebmail("https://www.google.com/search?q=gmail"))
        assertFalse(LocalTools.isWebmail("https://notgmail.com"))
        assertFalse(LocalTools.isWebmail("https://booking.com"))
    }

    @Test
    fun bareConsumerMailHostsAreWebmail() {
        for (u in listOf(
            "outlook.com", "www.outlook.com", "https://hotmail.com", "live.com", "https://login.live.com/login.srf",
            "https://account.proton.me/login", "https://www.icloud.com/mail", "icloud.com/mail/", "https://Mail.Google.com.",
        )) assertTrue(u, LocalTools.isWebmail(u))
        for (u in listOf("https://www.icloud.com/photos", "https://proton.me/pricing", "https://mailchimp.com", "")) {
            assertFalse(u, LocalTools.isWebmail(u))
        }
    }

    @Test
    fun addressesAStrictParserRejectsAreStillChecked() {
        assertTrue(LocalTools.isWebmail("https://mail.google.com/mail/u/0/#search/from:bob is:unread"))
        assertTrue(LocalTools.isWebmail("https://mail.google.com/a|b{c}"))
        assertTrue(LocalTools.isWebmail("https://user@mail.google.com:443/"))
        // The host is what follows the last @, as a browser reads it.
        assertFalse(LocalTools.isWebmail("https://mail.google.com@example.com/"))
    }

    @Test
    fun theAddressTheBrowserWouldLoadIsChecked() {
        // browser_open fixes these before loading, so the check must too.
        for (u in listOf("https:mail.google.com", "https//mail.google.com", "\"gmail.com\"", "<https://gmail.com>", "gmail.com/?next=https://example.com")) {
            assertTrue(u, LocalTools.isWebmail(u))
        }
        // Spellings Chromium reads as Gmail.
        for (u in listOf("https:\\\\mail.google.com", "https:///\\mail.google.com", "https://%6Dail.google.com", "https://ｍａｉｌ.google.com")) {
            assertTrue(u, LocalTools.isWebmail(u))
        }
        assertFalse(LocalTools.isWebmail("example.com/?next=https://mail.google.com"))
        assertFalse(LocalTools.isWebmail("gmail login help"))
    }

    @Test
    fun googleSignInOnTheWayToGmailIsWebmail() {
        assertTrue(LocalTools.isWebmail("https://accounts.google.com/ServiceLogin?service=mail&continue=https%3A%2F%2Fmail.google.com%2Fmail%2F"))
        assertTrue(LocalTools.isWebmail("https://accounts.google.com/v3/signin/identifier?continue=https://mail.google.com/mail/u/0/"))
        assertFalse(LocalTools.isWebmail("https://accounts.google.com/v3/signin/identifier?continue=https://www.youtube.com/"))
    }
}
