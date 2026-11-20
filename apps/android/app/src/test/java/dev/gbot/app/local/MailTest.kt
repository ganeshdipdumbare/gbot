package dev.gbot.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MailTest {
    private fun crlf(s: String) = s.trimIndent().replace("\n", "\r\n").toByteArray()

    @Test
    fun knowsCommonProviders() {
        assertEquals("imap.gmail.com", Mail.guessHost("me@gmail.com"))
        assertEquals("imap.mail.me.com", Mail.guessHost("Me@iCloud.com"))
        assertEquals("imap.example.org", Mail.guessHost("me@example.org"))
        assertEquals("", Mail.guessHost("nobody"))
    }

    @Test
    fun searchAndFetchReadLiteralsAndStatus() {
        val header = "From: =?UTF-8?B?w4lsaXNl?= <elise@example.com>\r\nSubject: Lunch\r\nDate: Thu, 19 Nov 2026 12:00:00 +0000\r\n\r\n"
        val server = "* SEARCH 3 7 12\r\na1 OK done\r\n" +
            "* 1 FETCH (UID 12 FLAGS (\\Seen) BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {${header.toByteArray().size}}\r\n$header)\r\n" +
            "a2 OK done\r\n"
        val sent = ByteArrayOutputStream()
        val imap = Imap.over(ByteArrayInputStream(server.toByteArray()), sent)
        assertEquals(listOf(3L, 7L, 12L), imap.search(listOf("ALL"), utf8 = false))
        val f = imap.fetch(listOf(12), "(UID FLAGS BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])").single()
        assertEquals(12L, f.uid)
        assertTrue(f.flags.contains("\\Seen"))
        val h = Mime.headers(f.literalAfter("BODY[HEADER")!!)
        assertEquals("Élise <elise@example.com>", Mime.decodeWords(h["from"]!!))
        assertEquals("Lunch", h["subject"])
        assertTrue(sent.toString().contains("a1 UID SEARCH ALL\r\n"))
    }

    @Test
    fun loginRefusalIsALoginError() {
        val imap = Imap.over(ByteArrayInputStream("a1 NO [AUTHENTICATIONFAILED] Invalid credentials\r\n".toByteArray()), ByteArrayOutputStream())
        val e = runCatching { imap.login("me@gmail.com", "wrong") }.exceptionOrNull()
        assertTrue(e is ImapError && e.login)
        assertTrue(Mail.explain(e as Exception, Mail.Account("me@gmail.com", "imap.gmail.com", 993, "x")).contains("app password"))
    }

    @Test
    fun nonAsciiPasswordGoesAsALiteral() {
        val sent = ByteArrayOutputStream()
        val imap = Imap.over(ByteArrayInputStream("+ go ahead\r\na1 OK in\r\n".toByteArray()), sent)
        imap.login("me@example.com", "pässwörd")
        val out = sent.toString(Charsets.UTF_8)
        assertTrue(out, out.startsWith("a1 LOGIN \"me@example.com\" {10}\r\npässwörd\r\n"))
    }

    @Test
    fun literalSizePatternsAvoidIcuBraceEscapes() {
        // The Connect inbox crash was Pattern.compile on Android ICU rejecting \{ \}.
        // Desktop JVM accepts those escapes, so a match-only unit test would still
        // pass if someone reverted Mail.kt. Guard the pattern text itself.
        for (p in listOf(Imap.LITERAL_SIZE, Imap.LITERAL_SIZE_AT_END)) {
            assertFalse("ICU-unsafe \\{ in $p", p.contains("\\{"))
            assertFalse("ICU-unsafe \\} in $p", p.contains("\\}"))
            assertTrue(p, Regex(p).containsMatchIn("x{12}"))
            assertTrue(p, Regex(p).containsMatchIn("x{12+}"))
        }
        assertTrue(Regex(Imap.LITERAL_SIZE_AT_END).matches("{12}"))
        assertFalse(Regex(Imap.LITERAL_SIZE_AT_END).containsMatchIn("x{12}y"))
    }

    @Test
    fun quotesAreEscaped() {
        val sent = ByteArrayOutputStream()
        Imap.over(ByteArrayInputStream("a1 OK\r\n".toByteArray()), sent).login("a@b.c", "x\"y\\z")
        assertTrue(sent.toString().contains("\"x\\\"y\\\\z\""))
    }

    @Test
    fun multipartPrefersPlainTextAndListsAttachments() {
        val raw = crlf(
            """
            From: Sam <sam@example.com>
            Subject: =?iso-8859-1?Q?Caf=E9_plans?=
            Content-Type: multipart/mixed; boundary="outer"

            --outer
            Content-Type: multipart/alternative; boundary=inner

            --inner
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: quoted-printable

            Hi there =E2=80=94 see you at 5.=
             Bring the doc.
            --inner
            Content-Type: text/html; charset=utf-8

            <p>Hi there</p>
            --inner--
            --outer
            Content-Type: application/pdf; name="plan.pdf"
            Content-Disposition: attachment; filename="plan.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --outer--
            """,
        )
        val m = Mime.parse(raw)
        assertEquals("Café plans", Mime.decodeWords(m.headers["subject"]!!))
        assertEquals("Hi there — see you at 5. Bring the doc.", m.text)
        assertEquals(listOf("plan.pdf"), m.attachments)
    }

    @Test
    fun htmlOnlyBecomesReadableText() {
        val raw = crlf(
            """
            Subject: News
            Content-Type: text/html; charset=utf-8
            Content-Transfer-Encoding: base64

            ${java.util.Base64.getEncoder().encodeToString("<html><head><style>p{}</style></head><body><p>Hello&nbsp;Ana</p><ul><li>One</li><li>Two</li></ul><a href=\"https://x.example/a\">Open</a></body></html>".toByteArray())}
            """,
        )
        val text = Mime.parse(raw).text
        assertTrue(text, text.contains("Hello Ana"))
        assertTrue(text, text.contains("• One"))
        assertTrue(text, text.contains("Open (https://x.example/a)"))
        assertFalse(text.contains("p{}"))
    }

    @Test
    fun adjacentEncodedWordsJoin() {
        assertEquals("Hello world", Mime.decodeWords("=?UTF-8?Q?Hello_?= =?UTF-8?Q?world?="))
    }

    @Test
    fun datesForSearch() {
        assertEquals("1-Nov-2026", Mail.imapDate("2026-11-01"))
        assertEquals(null, Mail.imapDate("yesterday"))
    }

    @Test
    fun mailOnlyTaintDoesNotAskForMoreMail() {
        val t = RunTaint()
        t.mark("email_list")
        assertTrue(t.on)
        assertFalse(t.taints("email_read"))
        assertTrue(t.taints("calendar_list"))
        t.mark("web_fetch")
        assertTrue(t.taints("email_read"))
    }

    @Test
    fun promptSaysHowToConnect() {
        assertTrue(LocalTools.mailRule(emailOn = true, inbox = false).contains("Connect inbox"))
        assertTrue(LocalTools.mailRule(emailOn = true, inbox = true).contains("email_list"))
        assertFalse(LocalTools.mailRule(emailOn = true, inbox = true).contains("can't read"))
    }

    @Test
    fun badEntitiesStayAsWritten() {
        val text = Mime.htmlToText("<p>a &#99999999; b &#x110000; c &#233;</p>")
        assertEquals("a &#99999999; b &#x110000; c é", text)
    }

    @Test
    fun partWithoutHeadersKeepsItsFirstParagraph() {
        val raw = crlf(
            """
            Content-Type: multipart/mixed; boundary=b

            --b

            First paragraph.

            Second paragraph.
            --b--
            """,
        )
        assertEquals("First paragraph.\n\nSecond paragraph.", Mime.parse(raw).text)
    }
}
