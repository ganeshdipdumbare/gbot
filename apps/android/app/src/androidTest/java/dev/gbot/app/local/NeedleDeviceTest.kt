package dev.gbot.app.local

import androidx.test.platform.app.InstrumentationRegistry
import dev.gbot.app.net.json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs the real Needle runtime on an arm64 device. Needs needle3.cact in the app's files/needle folder. */
class NeedleDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tool(name: String, desc: String, props: String) = ToolSchema(
        name, desc, json.parseToJsonElement("""{"type":"object","properties":$props,"required":[]}""") as JsonObject,
    )

    private val tools = listOf(
        tool("web_search", "Search the web for current information.", """{"query":{"type":"string","description":"What to search for"}}"""),
        tool("routine_list", "List the person's routines.", "{}"),
        tool("calendar_list", "List calendar events between two times.", """{"from":{"type":"string"},"to":{"type":"string"}}"""),
        tool("contact_search", "Find a person in the contacts.", """{"name":{"type":"string"}}"""),
    )

    @Test fun routesRequestsToTools() {
        assumeTrue("weights not on device", Needle.ready(ctx))
        val search = Needle.route(ctx, "what's the weather in Pune right now?", tools, 0.0)
        println("NEEDLE search -> $search")
        assertNotNull(search)
        assertEquals("web_search", search!!.name)
        val routines = Needle.route(ctx, "show me my routines", tools, 0.0)
        println("NEEDLE routines -> $routines")
        assertEquals("routine_list", routines?.name)
        val contact = Needle.route(ctx, "find Priya's number", tools, 0.0)
        println("NEEDLE contact -> $contact")
        assertEquals("contact_search", contact?.name)
        val chat = Needle.route(ctx, "write me a poem about autumn", tools, 0.7)
        println("NEEDLE poem -> $chat")
        assertTrue(chat == null)
    }
}
