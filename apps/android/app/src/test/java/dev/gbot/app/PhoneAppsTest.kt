package dev.gbot.app

import dev.gbot.app.local.PhoneApps
import java.time.ZoneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PhoneAppsTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun `a date alone is an all-day event at midnight UTC, whatever the phone's zone`() {
        val (ms, allDay) = PhoneApps.parse("2026-11-20", berlin)!!
        assertTrue(allDay)
        // 2026-11-20T00:00Z, not Berlin's midnight, which is still the 19th in UTC.
        assertEquals(java.time.Instant.parse("2026-11-20T00:00:00Z").toEpochMilli(), ms)
    }

    @Test fun `a date with a time is read in the phone's zone`() {
        val (ms, allDay) = PhoneApps.parse("2026-11-20T15:00", berlin)!!
        assertFalse(allDay)
        assertEquals(java.time.Instant.parse("2026-11-20T14:00:00Z").toEpochMilli(), ms)
    }

    @Test fun `a space instead of T still parses and nonsense does not`() {
        assertEquals(PhoneApps.parse("2026-11-20T15:00", berlin), PhoneApps.parse("2026-11-20 15:00", berlin))
        assertNull(PhoneApps.parse("next tuesday", berlin))
        assertNull(PhoneApps.parse("", berlin))
    }

    @Test fun `only tools that change something ask first`() {
        assertTrue("calendar_add" in PhoneApps.needsApproval)
        assertTrue("email_draft" in PhoneApps.needsApproval)
        assertFalse("calendar_list" in PhoneApps.needsApproval)
        assertFalse("contact_search" in PhoneApps.needsApproval)
    }

    @Test fun `only phone-number-looking queries are matched on their digits`() {
        assertTrue(PhoneApps.looksLikeNumber("+1 (555) 123-4567"))
        assertTrue(PhoneApps.looksLikeNumber("5551234567"))
        assertFalse(PhoneApps.looksLikeNumber("john1985@gmail.com"))
        assertFalse(PhoneApps.looksLikeNumber("Room 101"))
        assertFalse(PhoneApps.looksLikeNumber("42"))
    }
}
