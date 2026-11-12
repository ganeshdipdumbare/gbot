package dev.gbot.app

import dev.gbot.app.ui.expandArcs
import org.junit.Assert.assertEquals
import org.junit.Test

class ArcsTest {
    @Test fun splitsCompactFlags() {
        assertEquals("M8 10V7a4 4 0 0 1 8 0 v3", expandArcs("M8 10V7a4 4 0 018 0v3"))
        assertEquals("M5 11a7 7 0 0 0 14 0 M12 18v3", expandArcs("M5 11a7 7 0 0014 0M12 18v3"))
        assertEquals("M9 9a2 2 0 0 1 -1 1.73 l1 1", expandArcs("M9 9a2 2 0 0 1-1 1.73l1 1"))
    }
}
