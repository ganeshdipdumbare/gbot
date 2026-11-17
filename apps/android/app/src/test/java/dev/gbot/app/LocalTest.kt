package dev.gbot.app

import dev.gbot.app.local.Approval
import dev.gbot.app.local.BrowserController
import dev.gbot.app.local.Cron
import dev.gbot.app.local.canonicalArgs
import dev.gbot.app.local.canonicalTool
import dev.gbot.app.local.Mentions
import dev.gbot.app.local.LocalProviders
import dev.gbot.app.local.ModelMemory
import dev.gbot.app.local.choiceLines
import dev.gbot.app.local.normalizeName
import dev.gbot.app.local.pickPeer
import dev.gbot.app.local.plainText
import dev.gbot.app.local.unquote
import dev.gbot.app.local.usesComputer
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
    fun `fetching a page does not turn the computer on`() {
        assertFalse(usesComputer("web_fetch"))
        assertTrue(usesComputer("browser_open"))
        assertTrue(usesComputer("browser_click"))
        assertTrue(usesComputer("open_in_chrome"))
        assertTrue(usesComputer("vault_type"))
        // A browser name the phone doesn't have must not light the computer up.
        assertFalse(usesComputer("browser_teleport"))
    }

    @Test
    fun `browser names from elsewhere reach the phone's own tools`() {
        assertEquals("browser_open", canonicalTool("browser_navigate"))
        assertEquals("browser_open", canonicalTool("Browser_Search"))
        assertEquals("browser_read", canonicalTool("browser_snapshot"))
        assertEquals("browser_fill", canonicalTool("browser_type"))
        assertEquals("browser_enter", canonicalTool("browser_press"))
        assertEquals("browser_open", canonicalTool("browser_open"))
        assertEquals("web_fetch", canonicalTool("web_fetch"))
    }

    @Test
    fun `borrowed arguments land where the approval card and tool read them`() {
        fun obj(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        fun str(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull

        // A search's query becomes the url the card shows and the window opens.
        assertEquals("cheap flights", str(canonicalArgs("browser_open", obj("""{"query":"cheap flights"}""")), "url"))
        // The server's browser_type {ref, text, submit}.
        val typed = canonicalArgs("browser_fill", obj("""{"ref":3,"text":"hi","submit":true}"""))
        assertEquals("3", str(typed, "id"))
        assertEquals("hi", str(typed, "value"))
        assertEquals("true", str(typed, "submit"))
        assertEquals("5", str(canonicalArgs("browser_click", obj("""{"ref":5}""")), "id"))
        // The phone's own names win, and nothing is invented.
        assertEquals("https://a.b", str(canonicalArgs("browser_open", obj("""{"url":"https://a.b","query":"x"}""")), "url"))
        assertEquals(null, str(canonicalArgs("browser_enter", obj("""{"key":"Enter"}""")), "id"))
    }

    @Test
    fun `a quoted tool value loses its quotes`() {
        assertEquals("Joker", unquote("\"Joker\""))
        assertEquals("Jokes on demand", unquote(" 'Jokes on demand' "))
        assertEquals("Joker", unquote("Joker"))
        assertEquals("He said \"hi\"", unquote("He said \"hi\""))
    }

    @Test
    fun `an escaped tool value reads as plain text`() {
        assertEquals("Who's travelling?", plainText("\"Who's travelling?\""))
        assertEquals("Just me\n2 people", plainText("\"Just me\\n2 people\""))
        assertEquals("line one\nline two", plainText("line one\\nline two"))
        assertEquals("already plain", plainText("  already plain  "))
        assertEquals("keeps real\nnewlines", plainText("keeps real\nnewlines"))
    }

    @Test
    fun `choices come back one per line however they were packed`() {
        val packed = "\"Just me\\n2 people\\n3-4 people\\nLarger group\""
        assertEquals(listOf("Just me", "2 people", "3-4 people", "Larger group"), choiceLines(packed, 4))
        assertEquals(listOf("Tea", "Coffee"), choiceLines("[\"Tea\", \"Coffee\"]", 4))
        assertEquals(listOf("Tea", "Coffee"), choiceLines("- Tea\n- Coffee", 4))
        assertEquals(listOf("Tea"), choiceLines("Tea\nTea\n\n", 4))
        assertEquals(2, choiceLines("a1\nb2\nc3\nd4", 2).size)
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
    fun `the sentinel never reaches the chat`() {
        assertEquals("", dev.gbot.app.local.cleanReply("NO_REPLY"))
        assertEquals("", dev.gbot.app.local.cleanReply(" (NO_REPLY) "))
        assertEquals("", dev.gbot.app.local.cleanReply("`NO_REPLY`."))
        assertEquals("", dev.gbot.app.local.cleanReply("(no answer)"))
        assertEquals("", dev.gbot.app.local.cleanReply("   "))
        // A real answer that trails the marker keeps the answer, loses the marker.
        assertEquals("Here is the joke.", dev.gbot.app.local.cleanReply("Here is the joke. NO_REPLY"))
        assertEquals("Done.", dev.gbot.app.local.cleanReply("Done.\n\nNO_REPLY\n"))
        assertEquals("Nothing to add.", dev.gbot.app.local.cleanReply("Nothing to add. (no_reply)"))
    }

    // ---- The exchange card is what the chat UI opens on tap ----

    @Test
    fun `saying the same fact twice keeps one copy`() {
        assertTrue(dev.gbot.app.local.sameFact("Likes espresso", "likes espresso."))
        assertTrue(dev.gbot.app.local.sameFact("The person likes espresso in the morning", "Likes espresso in the morning"))
        // Short facts must match exactly: "tea" and "green tea" are different.
        assertFalse(dev.gbot.app.local.sameFact("tea", "green tea"))
        assertFalse(dev.gbot.app.local.sameFact("Likes tea", "Likes coffee"))
        assertFalse(dev.gbot.app.local.sameFact("", "anything"))
    }

    @Test
    fun `a query ranks the facts that share its words first`() {
        val now = java.time.Instant.parse("2026-11-12T00:00:00Z").toEpochMilli()
        val notes = listOf(
            dev.gbot.app.local.Note("1", "2026-11-11T00:00:00Z", "Flies to Berlin in December"),
            dev.gbot.app.local.Note("2", "2026-01-01T00:00:00Z", "Prefers window seats on flights"),
            dev.gbot.app.local.Note("3", "2026-11-12T00:00:00Z", "Drinks oat milk"),
        )
        assertEquals(listOf("1", "2"), dev.gbot.app.local.rankNotes(notes, "flights to berlin", 2, now).map { it.id })
        // No query at all: newest first.
        assertEquals(listOf("3", "1", "2"), dev.gbot.app.local.rankNotes(notes, "", 3, now).map { it.id })
    }

    @Test
    fun `the prompt carries the freshest facts, dated`() {
        val notes = listOf(
            dev.gbot.app.local.Note("old", "2026-01-02T00:00:00Z", "Old fact"),
            dev.gbot.app.local.Note("new", "2026-11-12T08:00:00Z", "New fact"),
        )
        val kept = dev.gbot.app.local.promptNotes(notes, 1)
        assertEquals(listOf("new"), kept.map { it.id })
        assertEquals("2026-11-12", dev.gbot.app.local.noteDay(kept[0].at))
    }

    @Test
    fun `only a message that needs a plan gets one`() {
        assertFalse(dev.gbot.app.local.worthPlanning("hi"))
        assertFalse(dev.gbot.app.local.worthPlanning("what's the weather?"))
        assertTrue(dev.gbot.app.local.worthPlanning("find me a flight and then book it"))
        assertTrue(dev.gbot.app.local.worthPlanning("compare these two phones"))
        assertTrue(dev.gbot.app.local.worthPlanning("do this\nand this\nand this"))
        assertTrue(dev.gbot.app.local.worthPlanning("a".repeat(200)))
    }

    @Test
    fun `a plan is trimmed and kept out of the chat`() {
        val raw = "\n WANT: the flights\n\nCONTEXT: none\nSTEPS: 1) search\n2) compare\n3) book\n4) confirm\n"
        val out = dev.gbot.app.local.cleanPlan(raw)
        assertEquals(6, out.lines().size)
        assertTrue(out.startsWith("WANT: the flights"))
        assertEquals("", dev.gbot.app.local.cleanPlan("  \n\n"))
        assertEquals("", dev.gbot.app.local.planBlock(""))
        assertTrue(dev.gbot.app.local.planBlock(out).contains(out))
    }

    @Test
    fun `a plan is read one step per line`() {
        assertEquals(
            listOf("Find the flights", "Compare prices", "Book the cheapest"),
            dev.gbot.app.local.planSteps("1. Find the flights\n2) Compare prices\n- Book the cheapest"),
        )
        assertEquals(emptyList<String>(), dev.gbot.app.local.planSteps("  \n — \n"))
    }

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

    @Test
    fun `a url gives the site to show while the bot works`() {
        assertEquals("linkedin.com", dev.gbot.app.local.hostOf("https://www.linkedin.com/jobs/"))
        assertEquals("example.com", dev.gbot.app.local.hostOf("http://example.com/x?y=1"))
        assertEquals("", dev.gbot.app.local.hostOf(""))
        assertEquals("", dev.gbot.app.local.hostOf("not a url"))
    }
}
