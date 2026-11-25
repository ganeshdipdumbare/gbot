package dev.gbot.app.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Links a bot writes, set right before they are drawn. */
class LinksTest {
    @Test fun normalizesWhatModelsWrite() {
        assertEquals("https://example.com", Links.normalize("example.com"))
        assertEquals("https://www.example.com/a", Links.normalize("www.example.com/a"))
        assertEquals("https://example.com/a", Links.normalize("<https://example.com/a>"))
        assertEquals("https://example.com/a%20b", Links.normalize("https://example.com/a b"))
        assertEquals("mailto:a@b.com", Links.normalize("mailto:a@b.com"))
        assertEquals("tel:+4930123", Links.normalize("tel:+49 30 123"))
        assertNull(Links.normalize("javascript:alert(1)"))
        assertNull(Links.normalize("/relative/path"))
        assertNull(Links.normalize("notes"))
        assertNull(Links.normalize("https://localhost"))
        // A file name is not a site, even where its ending is a real TLD.
        assertNull(Links.normalize("report.pdf"))
        assertNull(Links.normalize("invoice.zip"))
        assertEquals("https://example.com/report.pdf", Links.normalize("example.com/report.pdf"))
        assertEquals("https://linear.app", Links.normalize("linear.app"))
        assertEquals("https://bun.sh/docs", Links.normalize("bun.sh/docs"))
    }

    @Test fun addressesUriRefusesStillOpen() {
        assertEquals("https://example.com/s?q=a%7Cb", Links.normalize("https://example.com/s?q=a|b"))
        assertEquals("https://api.x.com/i?filter%5Bstatus%5D=open", Links.normalize("https://api.x.com/i?filter[status]=open"))
        assertEquals("https://x.com/100%25-off", Links.normalize("https://x.com/100%-off"))
        assertEquals("https://x.com/a%20b", Links.normalize("https://x.com/a%20b"))
    }

    @Test fun bareAddressesLeaveTheSentenceOut() {
        val found = Links.bare("See https://example.com/a. Or (https://en.wikipedia.org/wiki/Rust_(language)), and www.x.org!")
        assertEquals(listOf("https://example.com/a", "https://en.wikipedia.org/wiki/Rust_(language)", "https://www.x.org"), found.map { it.url })
        assertEquals("www.x.org", found.last().label)
    }

    @Test fun markdownAndBareLinksInCardText() {
        val found = Links.inText("Book at [Hotel Adlon](hoteladlon.de/rooms) or https://booking.com/x, then go.")
        assertEquals(listOf("Hotel Adlon" to "https://hoteladlon.de/rooms", "https://booking.com/x" to "https://booking.com/x"), found.map { it.label to it.url })
    }

    @Test fun aCardKeepsOnlyLinksAToolGave() {
        val args = Json.parseToJsonElement(
            """{"title":"t","components":[{"id":"root","component":"Text","text":"[Good](https://a.com/x) and [Made up](https://evil.com/?q=secret) and http://plain.com"}]}""",
        ).jsonObject
        val sf = Surfaces.build(args, allowLink = { it == "https://a.com/x" || it == "http://plain.com" }) { null }
        assertEquals(setOf("https://a.com/x"), Surfaces.links(sf.spec))
        // A tool's address with a | still opens once encoded.
        val raw = "https://a.com/s?q=a|b"
        val piped = Json.parseToJsonElement("""{"components":[{"id":"root","component":"Text","text":"[Search]($raw)"}]}""").jsonObject
        assertEquals(setOf("https://a.com/s?q=a%7Cb"), Surfaces.links(Surfaces.build(piped, allowLink = { it == raw }) { null }.spec))
    }
}
