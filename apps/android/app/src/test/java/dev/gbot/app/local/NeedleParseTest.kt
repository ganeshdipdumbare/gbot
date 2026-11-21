package dev.gbot.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeedleParseTest {
    private val known = setOf("web_search", "routine_list")
    private fun raw(calls: String, c: Double) =
        """{"type":"call","success":true,"function_calls":$calls,"confidence":$c}"""

    @Test fun readsOneConfidentCall() {
        val call = Needle.parse(raw("""[{"name":"web_search","arguments":{"query":"pune weather"}}]""", 0.47), known, 0.4)
        assertEquals("web_search", call?.name)
        assertEquals("pune weather", call?.arguments?.get("query")?.toString()?.trim('"'))
    }

    @Test fun ignoresLowConfidence() =
        assertNull(Needle.parse(raw("""[{"name":"routine_list","arguments":{}}]""", 0.2), known, 0.4))

    @Test fun ignoresUnknownToolsAndGuessedPlans() {
        assertNull(Needle.parse(raw("""[{"name":"rm_rf","arguments":{}}]""", 0.9), known, 0.4))
        assertNull(Needle.parse(raw("""[{"name":"web_search","arguments":{}},{"name":"routine_list","arguments":{}}]""", 0.9), known, 0.4))
        assertNull(Needle.parse(raw("[]", 0.9), known, 0.4))
        assertNull(Needle.parse("not json", known, 0.4))
    }
}
