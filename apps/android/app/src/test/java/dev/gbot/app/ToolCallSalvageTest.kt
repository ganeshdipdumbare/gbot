package dev.gbot.app

import dev.gbot.app.local.ToolSchema
import dev.gbot.app.local.isToolCallSyntaxFailure
import dev.gbot.app.local.salvageToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemma 4 E2B on a phone writes tool calls slightly wrong. The run should
 * read the call anyway instead of ending with the parser's complaint.
 */
class ToolCallSalvageTest {
    private fun schema(name: String, props: String, required: String): ToolSchema = ToolSchema(
        name, "",
        Json.parseToJsonElement("""{"type":"object","properties":$props,"required":$required}""") as JsonObject,
    )

    private val contract = schema(
        "task_contract",
        """{"goal":{"type":"string"},"checks":{"type":"string"}}""",
        """["goal","checks"]""",
    )
    private val routine = schema(
        "routine_update",
        """{"name":{"type":"string"},"active":{"type":"boolean"}}""",
        """["name","active"]""",
    )

    /** The call from a real phone run, which used to end the task. */
    private val real =
        """<|tool_call>call:task_contract{checks:<|"|>1) Ask user for destination, duration, and budget<|"|>,""" +
            """2) Implement itinerary suggestion logic based on inputs<|"|>,""" +
            """3) Verify that the bot correctly handles all three inputs before generating an itinerary<|"|>,""" +
            """goal:<|"|>Create a functional trip planner bot that gathers trip details and generates itinerary suggestions<|"|>}<tool_call|>"""

    @Test
    fun `the engine's own complaint is recognised`() {
        assertTrue(isToolCallSyntaxFailure(IllegalStateException("Status Code: 3. Message: Failed to parse tool calls from code block: $real")))
        assertTrue(isToolCallSyntaxFailure(RuntimeException("Failed to parse FC tool calls: FallThrough(ParseCancelledError(..))")))
        assertFalse(isToolCallSyntaxFailure(java.net.UnknownHostException("nowhere")))
        assertFalse(isToolCallSyntaxFailure(RuntimeException(null as String?)))
    }

    @Test
    fun `values crammed into one field come back one per line`() {
        val call = salvageToolCall(real, listOf(contract, routine))!!
        assertEquals("task_contract", call.name)
        assertEquals(
            "Create a functional trip planner bot that gathers trip details and generates itinerary suggestions",
            call.arguments["goal"]!!.jsonPrimitive.content,
        )
        assertEquals(
            listOf(
                "1) Ask user for destination, duration, and budget",
                "2) Implement itinerary suggestion logic based on inputs",
                "3) Verify that the bot correctly handles all three inputs before generating an itinerary",
            ),
            call.arguments["checks"]!!.jsonPrimitive.content.lines(),
        )
    }

    @Test
    fun `a well formed call survives the same path`() {
        val call = salvageToolCall("""call:task_contract{goal:<|"|>Ship it<|"|>,checks:<|"|>It runs<|"|>}""", listOf(contract))!!
        assertEquals("Ship it", call.arguments["goal"]!!.jsonPrimitive.content)
        assertEquals("It runs", call.arguments["checks"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a bracketed list reads as lines too`() {
        val call = salvageToolCall("""call:task_contract{goal:<|"|>Go<|"|>,checks:[<|"|>one<|"|>,<|"|>two<|"|>]}""", listOf(contract))!!
        assertEquals(listOf("one", "two"), call.arguments["checks"]!!.jsonPrimitive.content.lines())
    }

    @Test
    fun `a colon inside a sentence stays in the sentence`() {
        val call = salvageToolCall("""call:task_contract{goal:<|"|>Fix this: the name, maybe<|"|>}""", listOf(contract))!!
        assertEquals("Fix this: the name, maybe", call.arguments["goal"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plain JSON arguments work as well`() {
        val call = salvageToolCall("""call:routine_update{"name": "Morning", "active": true}""", listOf(routine))!!
        assertEquals("Morning", call.arguments["name"]!!.jsonPrimitive.content)
        assertEquals("true", call.arguments["active"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown tool or an empty call is not invented`() {
        assertNull(salvageToolCall("""call:make_coffee{strength:<|"|>strong<|"|>}""", listOf(contract)))
        assertNull(salvageToolCall("""call:task_contract{}""", listOf(contract)))
        assertNull(salvageToolCall("I'll just answer in words.", listOf(contract)))
    }

    @Test
    fun `a wrapped failure still gives up its text`() {
        val wrapped = RuntimeException("the run failed", IllegalStateException("Failed to parse tool calls from code block: $real"))
        assertTrue(isToolCallSyntaxFailure(wrapped))
        assertEquals("task_contract", salvageToolCall(dev.gbot.app.local.throwableText(wrapped), listOf(contract))!!.name)
    }
}
