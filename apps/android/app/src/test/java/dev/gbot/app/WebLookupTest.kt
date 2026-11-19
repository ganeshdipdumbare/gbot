package dev.gbot.app

import dev.gbot.app.local.WebLookup
import java.util.Base64
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class WebLookupTest {
    @Test fun searchUnwrapsTrackedLinksAndSkipsBingAndPlainHttp() {
        val enc = Base64.getUrlEncoder().withoutPadding().encodeToString("https://example.com/echo".toByteArray())
        val page = """<li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=1&amp;u=a1$enc&amp;ntb=1">Echo <strong>Hotel</strong></a></h2>
            <div class="b_caption"><p class="b_lineclamp2">Near the Mall &amp; cheap</p></div></li>
            <li class="b_algo"><h2><a href="https://www.bing.com/travel/x">Bing own</a></h2></li>
            <li class="b_algo"><h2><a href="http://plain.example.com/">Not https</a></h2></li>"""
        val hits = WebLookup.parseSearch(page)
        assertEquals(1, hits.size)
        assertEquals("Echo Hotel", hits[0].title)
        assertEquals("https://example.com/echo", hits[0].url)
        assertEquals("Near the Mall & cheap", hits[0].snippet)
    }

    @Test fun pageTextKeepsTitleAndPictureAndDropsScripts() {
        val page = """<html><head><title>Echo Hotel</title><meta property="og:image" content="/img/a.jpg"></head>
            <body><script>evil()</script><h1>Echo</h1><p>Twin rooms &amp; views</p></body></html>"""
        val out = WebLookup.pageText("https://example.com/h", page)
        assertTrue(out.contains("Title: Echo Hotel"))
        assertTrue(out.contains("Image: https://example.com/img/a.jpg"))
        assertTrue(out.contains("Twin rooms & views"))
        assertFalse(out.contains("evil"))
    }

    @Test fun onlyHttpsLinksSurvive() {
        assertEquals(null, WebLookup.https("http://a.com"))
        assertEquals(null, WebLookup.https("javascript:alert(1)"))
        assertEquals("https://a.com/x", WebLookup.https("https://a.com/x"))
    }

    @Test fun linksThePersonWroteLoseTrailingPunctuation() {
        assertEquals(setOf("https://a.com/x", "https://b.org"), WebLookup.linksIn("read https://a.com/x, and (https://b.org)."))
        assertEquals(emptySet<String>(), WebLookup.linksIn("see http://plain.com"))
    }
}
