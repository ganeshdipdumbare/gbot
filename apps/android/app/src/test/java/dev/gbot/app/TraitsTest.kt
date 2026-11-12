package dev.gbot.app

import dev.gbot.app.ui.traitsFromSeed
import org.junit.Assert.assertEquals
import org.junit.Test

// Expected values come from the web rig (packages/characters/src/traits.ts),
// so a bot looks the same on every device.
class TraitsTest {
    @Test fun matchesTheWebRig() {
        val want = mapOf(
            "brand" to "pill,bubblegum,wide",
            "hello" to "circle,cream,dot",
            "a1b2c3" to "diamond,cobalt,wide",
            "0f3c9e2a-1111-4d2c-9a7e-5b8d6c7e8f90" to "pill,mint,pill",
            "Pip" to "square,sky,wide",
            "empty" to "hexagon,cobalt,wide",
            "plug" to "hexagon,grape,wide",
            "éü✓" to "arch,sky,wide",
        )
        for ((seed, traits) in want) {
            val t = traitsFromSeed(seed)
            assertEquals(seed, traits, "${t.shape},${t.hue},${t.eyes}")
        }
    }
}
