package dev.gbot.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a run is asked to show its answer as a card before it ends. */
class CardNudgeTest {
    @Test fun lookedUpFactsAskForACard() {
        assertEquals(CardNudge.ASK, CardNudge.check("Berlin is 14°C with light rain, high 16°.", looked = true, shown = false))
    }

    @Test fun aTableOrFigureListAsksEvenWithoutALookup() {
        val table = "| Plan | Price |\n|---|---|\n| Basic | €5 |\n| Pro | €12 |"
        assertEquals(CardNudge.ASK, CardNudge.check(table, looked = false, shown = false))
        val bullets = "- Flight LH 123 departs 10:40\n- Gate B12\n- Boarding 10:05"
        assertEquals(CardNudge.ASK, CardNudge.check(bullets, looked = false, shown = false))
        val pairs = "Status: In transit\nCarrier: DHL\nExpected: Friday"
        assertEquals(CardNudge.ASK, CardNudge.check(pairs, looked = false, shown = false))
    }

    @Test fun conversationAndCodePassThrough() {
        assertNull(CardNudge.check("Sure, happy to help. What would you like to plan?", looked = false, shown = false))
        assertNull(CardNudge.check("Here it is:\n```kotlin\nval x = 1\n```", looked = true, shown = false))
        assertNull(CardNudge.check("Done, I saved it.", looked = true, shown = false))
        assertNull(CardNudge.check("x".repeat(3000) + " 1", looked = true, shown = false))
    }

    @Test fun aCardAlreadyShownIsEnough() {
        assertNull(CardNudge.check("Berlin is 14°C.", looked = true, shown = true))
    }

    @Test fun lookups() {
        assertTrue(CardNudge.isLookup("web_search", mcp = false))
        assertTrue(CardNudge.isLookup("calendar_list", mcp = false))
        assertTrue(CardNudge.isLookup("mcp_anything", mcp = true))
        assertFalse(CardNudge.isLookup("remember", mcp = false))
    }
}
