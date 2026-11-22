package dev.gbot.app

import dev.gbot.app.local.Approval
import dev.gbot.app.local.LocalResults
import dev.gbot.app.local.RunTaint
import dev.gbot.app.local.capped
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class ApprovalTaintTest {
    @Test fun privateReadsAskOnceTainted() {
        for (tool in listOf("calendar_list", "contact_search")) {
            assertEquals(tool, Approval.Choice.Run, Approval.decide("auto", null, false, tool, tainted = false))
            assertEquals(tool, Approval.Choice.Ask, Approval.decide("auto", null, false, tool, tainted = true))
            assertEquals(tool, Approval.Choice.Run, Approval.decide("never", null, false, tool, tainted = true))
        }
        // Research stays card-free: what web_fetch and card pictures may reach is limited instead.
        for (tool in listOf("web_search", "web_fetch", "show_ui")) {
            assertEquals(tool, Approval.Choice.Run, Approval.decide("auto", null, false, tool, tainted = true))
        }
    }

    @Test fun alwaysAllowNeverCoversSensitiveTools() {
        for (tool in listOf("vault_type", "routine_create", "bot_update", "browser_open")) {
            assertFalse(tool, Approval.canAlwaysAllow(tool))
            assertEquals(tool, Approval.Choice.Ask, Approval.decide("auto", "allow", true, tool))
        }
        assertEquals(Approval.Choice.Run, Approval.decide("auto", "allow", true, "calendar_add"))
    }

    @Test fun slackRunsStartTainted() {
        val t = RunTaint(initial = true)
        assertTrue(t.on)
        val clean = RunTaint()
        assertFalse(clean.on)
        clean.mark()
        assertTrue(clean.on)
    }

    @Test fun privateAndCarrierNatAddressesAreNotPublic() {
        for (a in listOf("10.0.0.1", "192.168.1.1", "127.0.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.1", "::1", "fd00::1")) {
            assertFalse(a, LocalResults.isPublic(InetAddress.getByName(a)))
        }
        assertTrue(LocalResults.isPublic(InetAddress.getByName("93.184.216.34")))
    }

    @Test fun cappedReadStopsAtTheCap() {
        assertEquals(10, "0123456789".toResponseBody().capped(10).size)
        val thrown = runCatching { ByteArray(2048).toResponseBody().capped(1024) }.exceptionOrNull()
        assertTrue(thrown is IOException)
    }
}
