package dev.gbot.app

import dev.gbot.app.local.Approval
import dev.gbot.app.local.BrowserController
import dev.gbot.app.local.Cron
import dev.gbot.app.local.Mentions
import dev.gbot.app.local.LocalProviders
import dev.gbot.app.local.ModelMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTest {
    @Test
    fun `read only tools run without asking`() {
        assertEquals(Approval.Choice.Run, Approval.decide("ask", null, touchesTheWorld = false))
        assertEquals(Approval.Choice.Run, Approval.decide("auto", null, touchesTheWorld = false))
    }

    @Test
    fun `changing tools ask in auto and ask, run in never`() {
        assertEquals(Approval.Choice.Ask, Approval.decide("auto", null, touchesTheWorld = true))
        assertEquals(Approval.Choice.Ask, Approval.decide("ask", null, touchesTheWorld = true))
        assertEquals(Approval.Choice.Run, Approval.decide("never", null, touchesTheWorld = true))
    }

    @Test
    fun `rules win over the mode`() {
        assertEquals(Approval.Choice.Refuse, Approval.decide("never", "block", touchesTheWorld = true))
        assertEquals(Approval.Choice.Run, Approval.decide("ask", "allow", touchesTheWorld = true))
    }

    @Test
    fun `a bare domain gets https`() {
        assertEquals("https://example.com", BrowserController.normalize("example.com"))
        assertEquals("https://example.com/jobs", BrowserController.normalize("https://example.com/jobs"))
    }

    @Test
    fun `words become a search`() {
        assertTrue(BrowserController.normalize("android jobs remote").startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun `a daily schedule lands on the next 9am`() {
        val at = Cron.parse("0 9 * * *")!!.next(java.time.ZonedDateTime.of(2026, 5, 4, 8, 30, 0, 0, java.time.ZoneId.of("UTC")))!!
        assertEquals(java.time.LocalTime.of(9, 0), at.toLocalTime())
        assertEquals(4, at.dayOfMonth)
    }

    @Test
    fun `presets and bad schedules`() {
        assertEquals(java.time.LocalTime.of(9, 0), Cron.parse("@daily")!!.next(java.time.ZonedDateTime.of(2026, 5, 4, 8, 30, 0, 0, java.time.ZoneId.of("UTC")))!!.toLocalTime())
        assertTrue(Cron.parse("0 9 * *") == null)
        assertTrue(Cron.parse("99 9 * * *") == null)
    }

    @Test
    fun `mention rules match the server`() {
        assertTrue(Mentions.matches("hey @Grok Bot can you look", "Grok Bot"))
        assertTrue(Mentions.matches("@Al, take this", "Al"))
        assertFalse(Mentions.matches("email al@example.com", "Al"))
        assertFalse(Mentions.matches("@Alex please", "Al"))
    }

    @Test
    fun `the idle window is safe for any stored value`() {
        assertEquals(2, ModelMemory.minutesOf(null))
        assertEquals(2, ModelMemory.minutesOf("oops"))
        assertEquals(2, ModelMemory.minutesOf("-4"))
        assertEquals(10, ModelMemory.minutesOf("10"))
        assertEquals(0, ModelMemory.minutesOf("0"))
    }

    @Test
    fun `a bare provider host gets the right path`() {
        assertEquals("https://api.openai.com/v1", LocalProviders.normalizeBase("openai", "https://api.openai.com"))
        assertEquals("http://10.0.2.2:1234/v1", LocalProviders.normalizeBase("openai", "http://10.0.2.2:1234"))
        assertEquals("http://10.0.2.2:1234/v1", LocalProviders.normalizeBase("openai", "http://10.0.2.2:1234/v1/"))
        assertEquals("https://api.anthropic.com", LocalProviders.normalizeBase("anthropic", "https://api.anthropic.com"))
        assertEquals("https://api.openai.com/v1", LocalProviders.normalizeBase("openai", ""))
    }

    @Test
    fun `messy urls from the model are cleaned`() {
        assertEquals("https://example.com", BrowserController.normalize("\"https//example.com%22"))
        assertEquals("https://example.com", BrowserController.normalize("https:example.com"))
        assertEquals("https://example.com", BrowserController.normalize("https://\"example.com\""))
        assertEquals("https://example.com", BrowserController.normalize("//example.com"))
        assertEquals("https://example.com/jobs", BrowserController.normalize("https://example.com/jobs"))
    }

    @Test
    fun `quotes and newlines survive the trip into javascript`() {
        val q = BrowserController.quote("a \"quoted\" line\nnext \\ back")
        assertTrue(q.startsWith("\""))
        assertFalse(q.contains("\n"))
    }
}
