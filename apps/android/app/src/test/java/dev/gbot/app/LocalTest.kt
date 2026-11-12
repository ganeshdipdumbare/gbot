package dev.gbot.app

import dev.gbot.app.local.Approval
import dev.gbot.app.local.BrowserController
import dev.gbot.app.local.Cron
import dev.gbot.app.local.Mentions
import dev.gbot.app.local.LocalProviders
import dev.gbot.app.local.ModelMemory
import dev.gbot.app.local.normalizeName
import dev.gbot.app.local.pickPeer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

    // ---- A refresh must not make a busy bot look idle ----

    private fun busy(detail: String? = null) = dev.gbot.app.net.Presence(state = "working", detail = detail)

    @Test
    fun `a bot list that omits a busy bot leaves it alone`() {
        // The on-device brain reports no presence at all, and a queued
        // run has none on the server yet.
        val now = 1_000_000L
        val out = dev.gbot.app.data.mergePresence(
            server = emptyMap(),
            local = mapOf("b1" to busy("Reading the diff")),
            changedAt = mapOf("b1" to now - 5_000),
            since = now,
        )
        assertEquals("working", out["b1"]?.state)
        assertEquals("Reading the diff", out["b1"]?.detail)
    }

    @Test
    fun `the list wins for a bot it does mention`() {
        val now = 1_000_000L
        val out = dev.gbot.app.data.mergePresence(
            server = mapOf("b1" to dev.gbot.app.net.Presence(state = "idle")),
            local = mapOf("b1" to busy()),
            changedAt = mapOf("b1" to now - 5_000),
            since = now,
        )
        assertEquals("idle", out["b1"]?.state)
    }

    @Test
    fun `a status that changed mid-fetch survives`() {
        val now = 1_000_000L
        val out = dev.gbot.app.data.mergePresence(
            server = mapOf("b1" to dev.gbot.app.net.Presence(state = "idle")),
            local = mapOf("b1" to busy()),
            changedAt = mapOf("b1" to now + 10),
            since = now,
        )
        assertEquals("working", out["b1"]?.state)
    }

    @Test
    fun `a stale busy status is eventually let go`() {
        val now = 1_000_000L
        val out = dev.gbot.app.data.mergePresence(
            server = emptyMap(),
            local = mapOf("b1" to busy()),
            changedAt = mapOf("b1" to now - dev.gbot.app.data.PRESENCE_GRACE - 1),
            since = now,
        )
        assertEquals(null, out["b1"])
    }

    @Test
    fun `going idle locally clears it even if the list still says busy`() {
        val now = 1_000_000L
        val out = dev.gbot.app.data.mergePresence(
            server = mapOf("b1" to busy()),
            local = emptyMap(),
            changedAt = mapOf("b1" to now + 10),
            since = now,
        )
        assertEquals(null, out["b1"])
    }

    // ---- A small model decorates the name it was given ----

    private val roster = listOf(
        dev.gbot.app.net.Bot(id = "1", name = "Taro"),
        dev.gbot.app.net.Bot(id = "2", name = "Remy"),
        dev.gbot.app.net.Bot(id = "3", name = "Taro Two"),
    )

    @Test
    fun `the decoration around a name is ignored`() {
        assertEquals("Taro", normalizeName("@Taro"))
        assertEquals("Taro", normalizeName(" “Taro” "))
        assertEquals("Taro", normalizeName("Taro."))
        assertEquals("Taro", normalizeName("Taro\u00A0"))
        assertEquals("Taro", normalizeName("\u200BTaro\u200B"))
        assertEquals("", normalizeName("  "))
    }

    @Test
    fun `a decorated name still finds its bot`() {
        // This exact shape produced: No bot named Taro. You can message Taro.
        assertEquals("2", pickPeer(" @Remy ", roster)?.id)
        assertEquals("1", pickPeer("“Taro”", roster)?.id)
        assertEquals("1", pickPeer("@Taro, say hello", roster)?.id)
        assertEquals("1", pickPeer("Taro", roster)?.id)
    }

    @Test
    fun `an ambiguous partial is not guessed`() {
        assertEquals(null, pickPeer("Tar", roster))
        assertEquals(null, pickPeer("Nobody", roster))
        assertEquals(null, pickPeer("", roster))
        assertEquals(null, pickPeer("Taro", emptyList()))
    }

    // ---- A bot answering a bot: what is worth carrying back ----

    @Test
    fun `only a real answer is carried back to the sender`() {
        assertTrue(dev.gbot.app.local.carriesAnswer("The report is ready."))
        assertFalse(dev.gbot.app.local.carriesAnswer("NO_REPLY"))
        assertFalse(dev.gbot.app.local.carriesAnswer(" `NO_REPLY`. "))
        assertFalse(dev.gbot.app.local.carriesAnswer("(no answer)"))
        assertFalse(dev.gbot.app.local.carriesAnswer("   "))
    }

    @Test
    fun `the sentinel is recognised, so it is never shown as a reply`() {
        assertTrue(dev.gbot.app.local.isNoReply("NO_REPLY"))
        assertTrue(dev.gbot.app.local.isNoReply(" `NO_REPLY`. "))
        assertTrue(dev.gbot.app.local.isNoReply("no_reply"))
        assertFalse(dev.gbot.app.local.isNoReply("Here is the joke."))
    }

    // ---- The exchange card is what the chat UI opens on tap ----

    @Test
    fun `the main bot wears the app mark`() {
        val t = dev.gbot.app.ui.resolveLook(dev.gbot.app.local.MAIN_LOOK, "any-seed")
        assertEquals("circle", t.shape)
        assertEquals("white", t.hue)
        assertEquals("pill", t.eyes)
        // White is the mark's colour only: it must never be seeded onto a bot.
        assertFalse(dev.gbot.app.ui.hueNames.contains("white"))
    }

    @Test
    fun `the roster reads as one line per bot`() {
        val bots = listOf(
            dev.gbot.app.net.Bot(id = "1", name = "Taro", title = "Jokes", model = "local:x"),
            dev.gbot.app.net.Bot(id = "2", name = "Pip", title = "", model = ""),
        )
        val out = dev.gbot.app.local.rosterSummary(bots)
        assertTrue(out.contains("- Taro — Jokes (local:x)"))
        assertTrue(out.contains("- Pip — no title (default model)"))
        assertEquals("There are no bots yet.", dev.gbot.app.local.rosterSummary(emptyList()))
    }

    @Test
    fun `an exchange card carries the keys the popup reads`() {
        val sent = dev.gbot.app.local.exchangeData("a", "b", "say hi to Pip")
        assertEquals("botmsg", sent["kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals("a", sent["from"]?.jsonPrimitive?.contentOrNull)
        assertEquals("b", sent["to"]?.jsonPrimitive?.contentOrNull)
        assertEquals("say hi to Pip", sent["message"]?.jsonPrimitive?.contentOrNull)
        assertEquals("sent", sent["status"]?.jsonPrimitive?.contentOrNull)
    }
}
