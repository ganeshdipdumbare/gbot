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

    private val two = listOf("me@gmail.com", "work@corp.com")

    @Test fun `one account or none means no question and no filter`() {
        assertEquals(PhoneApps.Pick.Use(null), PhoneApps.pick(listOf("me@gmail.com"), "", null))
        assertEquals(PhoneApps.Pick.Use(null), PhoneApps.pick(emptyList(), "", null))
    }

    @Test fun `several accounts and no choice asks the person with the list`() {
        val p = PhoneApps.pick(two, "", null)
        assertEquals(PhoneApps.Pick.Ask(two), p)
        val text = PhoneApps.askWhich(p, allowAll = true)!!
        assertTrue(text.contains("ask_user") && text.contains("me@gmail.com") && text.contains("work@corp.com") && text.contains("All accounts"))
        assertFalse(PhoneApps.askWhich(p, allowAll = false)!!.contains("All accounts"))
    }

    @Test fun `a named account is matched loosely, and all means every account`() {
        assertEquals(PhoneApps.Pick.Use("work@corp.com"), PhoneApps.pick(two, "WORK@corp.com", null))
        assertEquals(PhoneApps.Pick.Use("work@corp.com"), PhoneApps.pick(two, "work", null))
        assertEquals(PhoneApps.Pick.Use(null), PhoneApps.pick(two, "all", null))
        assertTrue(PhoneApps.pick(two, "other@x.com", null) is PhoneApps.Pick.Unknown)
    }

    @Test fun `the last chosen account is reused while it still exists`() {
        assertEquals(PhoneApps.Pick.Use("work@corp.com"), PhoneApps.pick(two, "", "work@corp.com"))
        assertEquals(PhoneApps.Pick.Ask(two), PhoneApps.pick(two, "", "gone@x.com"))
    }
}
