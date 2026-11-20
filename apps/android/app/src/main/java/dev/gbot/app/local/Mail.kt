package dev.gbot.app.local

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Reading the person's inbox straight from the phone, over IMAP. The
 * address and an app password live in this app's private preferences, like
 * vault values and provider keys; nothing goes through a server. Every
 * read uses EXAMINE and BODY.PEEK, so nothing is marked read or changed.
 */
object Mail {
    data class Account(val email: String, val host: String, val port: Int, val password: String)

    /** A known provider: where its IMAP server is, and where to make an app password. */
    data class Provider(val name: String, val host: String, val appPasswordUrl: String?, val note: String)

    private val providers = listOf(
        listOf("gmail.com", "googlemail.com") to Provider(
            "Gmail", "imap.gmail.com", "https://myaccount.google.com/apppasswords",
            "Google needs 2-Step Verification on, then an app password (16 letters).",
        ),
        listOf("icloud.com", "me.com", "mac.com") to Provider(
            "iCloud Mail", "imap.mail.me.com", "https://account.apple.com/account/manage",
            "Make an app-specific password under Sign-In and Security.",
        ),
        listOf("yahoo.com", "ymail.com", "rocketmail.com") to Provider(
            "Yahoo Mail", "imap.mail.yahoo.com", "https://login.yahoo.com/account/security",
            "Generate an app password under Account security.",
        ),
        listOf("outlook.com", "hotmail.com", "live.com", "msn.com") to Provider(
            "Outlook", "outlook.office365.com", "https://account.live.com/proofs/AppPassword",
            "Needs two-step verification and an app password; some Microsoft accounts no longer allow IMAP passwords.",
        ),
        listOf("aol.com") to Provider("AOL Mail", "imap.aol.com", "https://login.aol.com/account/security", "Generate an app password under Account security."),
        listOf("fastmail.com", "fastmail.fm") to Provider("Fastmail", "imap.fastmail.com", "https://app.fastmail.com/settings/security/apps", "Make an app password with IMAP access."),
        listOf("zoho.com", "zohomail.com") to Provider("Zoho Mail", "imap.zoho.com", "https://accounts.zoho.com/home#security/app_password", "Turn on IMAP in Zoho Mail, then make an app password."),
        listOf("gmx.com", "gmx.net", "gmx.de") to Provider("GMX", "imap.gmx.com", null, "Turn on IMAP access in GMX settings first."),
        listOf("proton.me", "protonmail.com") to Provider("Proton Mail", "", null, "Proton needs its Bridge app for IMAP, which Android doesn't have."),
    )

    /** The provider for an address, by its domain. */
    fun providerFor(email: String): Provider? {
        val domain = email.substringAfterLast('@', "").trim().lowercase()
        if (domain.isEmpty()) return null
        return providers.firstOrNull { (domains, _) -> domains.any { domain == it } }?.second
    }

    /** A sensible guess for an unknown domain: most hosts answer at imap.<domain>. */
    fun guessHost(email: String): String = providerFor(email)?.host ?: email.substringAfterLast('@', "").trim().lowercase().let { if (it.isEmpty()) "" else "imap.$it" }

    // ---- Saved account ------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gbot", Context.MODE_PRIVATE)

    fun account(ctx: Context): Account? {
        val p = prefs(ctx)
        val email = p.getString("mail_email", null) ?: return null
        val password = p.getString("mail_password", null) ?: return null
        return Account(email, p.getString("mail_host", null) ?: guessHost(email), p.getInt("mail_port", 993), password)
    }

    fun connected(ctx: Context) = account(ctx) != null

    fun save(ctx: Context, a: Account) {
        prefs(ctx).edit().putString("mail_email", a.email).putString("mail_host", a.host).putInt("mail_port", a.port)
            .putString("mail_password", a.password).apply()
    }

    fun forget(ctx: Context) {
        prefs(ctx).edit().remove("mail_email").remove("mail_host").remove("mail_port").remove("mail_password").apply()
    }

    /** Signs in once and opens the inbox, to check an account before it is saved. Null when it works. */
    fun test(a: Account): String? = if (a.host.isBlank()) providerFor(a.email)?.note ?: "Add the mail server name." else try {
        Imap.open(a).use { it.examine("INBOX") }
        null
    } catch (e: Exception) {
        explain(e, a)
    }

    /** A failure in words a person can act on. */
    internal fun explain(e: Exception, a: Account): String {
        val msg = e.message.orEmpty()
        return when {
            e is ImapError && e.login -> {
                val p = providerFor(a.email)
                "${p?.name ?: "Your mail provider"} refused the sign-in. Use an app password, not your normal password." +
                    (p?.note?.let { " $it" } ?: "")
            }
            e is java.net.UnknownHostException -> "Couldn't find the mail server ${a.host}. Check the server name, or your connection."
            e is java.net.SocketTimeoutException -> "${a.host} didn't answer in time. Check your connection and try again."
            e is java.net.ConnectException -> "Couldn't reach ${a.host}:${a.port}. Check the server name and port."
            e is javax.net.ssl.SSLException -> "Couldn't make a secure connection to ${a.host}: ${msg.ifEmpty { "TLS failed" }}"
            else -> msg.ifEmpty { e.javaClass.simpleName }
        }
    }

    // ---- What bots call -----------------------------------------------------

    class Summary(val id: String, val from: String, val subject: String, val date: String, val unread: Boolean, val attachment: Boolean)

    /** The newest messages in a folder, newest first, optionally only unread or matching text. */
    fun list(a: Account, folder: String, query: String, unread: Boolean, since: String, limit: Int): List<Summary> =
        Imap.open(a).use { imap ->
            imap.examine(folder)
            val criteria = buildList<Any> {
                if (unread) add("UNSEEN")
                imapDate(since)?.let { add("SINCE"); add(it) }
                if (query.isNotBlank()) { add("TEXT"); add(Imap.Str(query.trim())) }
                if (isEmpty()) add("ALL")
            }
            val utf8 = query.any { it.code > 127 }
            val uids = imap.search(criteria, utf8).sortedDescending().take(limit.coerceIn(1, 50))
            if (uids.isEmpty()) return@use emptyList()
            imap.fetch(uids, "(UID FLAGS BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])").mapNotNull { f ->
                val uid = f.uid ?: return@mapNotNull null
                val h = Mime.headers(f.literalAfter("BODY[HEADER") ?: ByteArray(0))
                Summary(
                    id = "$folder:$uid",
                    from = Mime.decodeWords(h["from"].orEmpty()).ifBlank { "(unknown sender)" },
                    subject = Mime.decodeWords(h["subject"].orEmpty()).ifBlank { "(no subject)" },
                    date = shownDate(h["date"].orEmpty()),
                    unread = !f.flags.contains("\\Seen", ignoreCase = true),
                    attachment = f.text.contains("\"attachment\"", ignoreCase = true),
                )
            }.sortedByDescending { it.id.substringAfterLast(':').toLongOrNull() ?: 0 }
        }

    class Message(val from: String, val to: String, val cc: String, val subject: String, val date: String, val text: String, val attachments: List<String>)

    /** One whole message, read without marking it read. Null when it is gone. */
    fun read(a: Account, id: String): Message? {
        val folder = id.substringBeforeLast(':', "INBOX").ifEmpty { "INBOX" }
        val uid = id.substringAfterLast(':').trim().toLongOrNull() ?: return null
        return Imap.open(a).use { imap ->
            imap.examine(folder)
            // Up to 8 MB of the raw message: enough for any text, without pulling down big attachments whole.
            val raw = imap.fetch(listOf(uid), "(UID BODY.PEEK[]<0.${8 * 1024 * 1024}>)").firstOrNull { it.uid == uid }?.literalAfter("BODY[]")
                ?: return@use null
            val m = Mime.parse(raw)
            Message(
                from = Mime.decodeWords(m.headers["from"].orEmpty()),
                to = Mime.decodeWords(m.headers["to"].orEmpty()),
                cc = Mime.decodeWords(m.headers["cc"].orEmpty()),
                subject = Mime.decodeWords(m.headers["subject"].orEmpty()).ifBlank { "(no subject)" },
                date = shownDate(m.headers["date"].orEmpty()),
                text = m.text,
                attachments = m.attachments,
            )
        }
    }

    /** IMAP's SINCE wants 1-Nov-2026. */
    internal fun imapDate(raw: String): String? {
        val d = runCatching { java.time.LocalDate.parse(raw.trim().take(10)) }.getOrNull() ?: return null
        return DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.US).format(d)
    }

    internal fun shownDate(raw: String): String {
        val clean = raw.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        val t = runCatching { ZonedDateTime.parse(clean, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(clean.replace(Regex("^[A-Za-z]{3},\\s*"), ""), DateTimeFormatter.ofPattern("d MMM yyyy HH:mm[:ss] Z", Locale.US)) }.getOrNull()
            ?: return clean
        return DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault()).format(t.withZoneSameInstant(ZoneId.systemDefault()))
    }
}

class ImapError(message: String, val login: Boolean = false) : IOException(message)

/** A small IMAP4rev1 client: just enough to sign in, open a folder, search and fetch. */
internal class Imap private constructor(private val input: InputStream, private val output: OutputStream, private val closer: Closeable) : Closeable {
    /** A string argument: quoted when it can be, sent as a literal otherwise. */
    class Str(val value: String)

    class Fetched(val uid: Long?, val flags: String, val text: String, val literals: List<ByteArray>) {
        /** The literal that answers an item, e.g. BODY[]: servers may send items in any order. */
        fun literalAfter(item: String): ByteArray? {
            val at = text.indexOf(item, ignoreCase = true)
            if (at < 0) return null
            val before = Regex(LITERAL_SIZE).findAll(text.substring(0, at)).count()
            return literals.getOrNull(before)
        }
    }

    private var tag = 0

    companion object {
        /**
         * IMAP `{n}` size markers in FETCH text. Use character classes (`[{]` / `[}]`),
         * never `\{` / `\}`: Android's ICU rejects those escapes and Connect inbox
         * crashed while compiling the pattern. The optional `+` is only defensive;
         * servers send synchronizing `{n}` (LITERAL+ is client→server, RFC 7888).
         */
        internal const val LITERAL_SIZE = "[{]\\d+[+]?[}]"
        internal const val LITERAL_SIZE_AT_END = "[{](\\d+)[+]?[}]$"

        /** Generous: a slow mobile network and a big mailbox can both take a while. */
        private const val TIMEOUT_MS = 120_000
        /** Reaching the server at all; a wrong host or port shouldn't wait for the OS TCP timeout. */
        private const val CONNECT_MS = 45_000

        fun open(a: Mail.Account): Imap {
            val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            try {
                socket.connect(java.net.InetSocketAddress(a.host, a.port), CONNECT_MS)
                socket.soTimeout = TIMEOUT_MS
                socket.startHandshake()
                // A bare SSLSocket doesn't check the certificate is for this host; HTTPS does, so ask it.
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(a.host, socket.session)) {
                    throw javax.net.ssl.SSLPeerUnverifiedException("The certificate isn't for ${a.host}")
                }
                val imap = Imap(BufferedInputStream(socket.inputStream), socket.outputStream, socket)
                imap.greeting()
                imap.login(a.email, a.password)
                return imap
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        /** For tests: talk over any pair of streams. */
        internal fun over(input: InputStream, output: OutputStream): Imap = Imap(BufferedInputStream(input), output, Closeable { })

        private fun quotable(s: String) = s.all { it.code in 0x20..0x7e }
        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    internal fun greeting() {
        val line = readLine()
        if (!line.startsWith("* OK") && !line.startsWith("* PREAUTH")) throw ImapError("The mail server said: ${line.take(200)}")
    }

    internal fun login(user: String, password: String) {
        try { command(listOf("LOGIN", Str(user), Str(password))) }
        catch (e: ImapError) { throw ImapError(e.message ?: "Sign-in failed", login = true) }
    }

    fun examine(folder: String) { command(listOf("EXAMINE", Str(folder))) }

    fun search(criteria: List<Any>, utf8: Boolean): List<Long> {
        val out = mutableListOf<Long>()
        val parts = listOf<Any>("UID", "SEARCH") + (if (utf8) listOf("CHARSET", "UTF-8") else emptyList()) + criteria
        command(parts).forEach { r ->
            if (r.text.startsWith("* SEARCH", ignoreCase = true)) {
                r.text.substring(8).trim().split(' ').mapNotNullTo(out) { it.toLongOrNull() }
            }
        }
        return out
    }

    fun fetch(uids: List<Long>, items: String): List<Fetched> =
        command(listOf("UID", "FETCH", uids.joinToString(","), items)).mapNotNull { r ->
            if (!Regex("^\\* \\d+ FETCH", RegexOption.IGNORE_CASE).containsMatchIn(r.text)) return@mapNotNull null
            Fetched(
                uid = Regex("UID (\\d+)", RegexOption.IGNORE_CASE).find(r.text)?.groupValues?.get(1)?.toLongOrNull(),
                flags = Regex("FLAGS \\(([^)]*)\\)", RegexOption.IGNORE_CASE).find(r.text)?.groupValues?.get(1).orEmpty(),
                text = r.text,
                literals = r.literals,
            )
        }

    override fun close() {
        runCatching { command(listOf("LOGOUT")) }
        runCatching { closer.close() }
    }

    // ---- Wire -------------------------------------------------------------

    /** One untagged response: its text, with each literal's place marked {n}, and the literal bytes. */
    internal class Response(val text: String, val literals: List<ByteArray>)

    /** Sends a command, literals included, and returns its untagged responses; throws unless it ends OK. */
    internal fun command(parts: List<Any>): List<Response> {
        val t = "a" + (++tag)
        val line = StringBuilder(t)
        for (p in parts) {
            line.append(' ')
            when (p) {
                is Str -> if (quotable(p.value)) line.append(quote(p.value)) else {
                    val bytes = p.value.toByteArray(Charsets.UTF_8)
                    line.append('{').append(bytes.size).append('}')
                    send(line.toString())
                    line.setLength(0)
                    // Wait for the go-ahead, then send the literal itself.
                    while (true) {
                        val r = readResponse()
                        if (r.text.startsWith("+")) break
                        if (r.text.startsWith("$t ")) throw ImapError(r.text.substring(t.length + 1).trim())
                    }
                    output.write(bytes)
                }
                else -> line.append(p.toString())
            }
        }
        send(line.toString())
        val out = mutableListOf<Response>()
        while (true) {
            val r = readResponse()
            if (r.text.startsWith("$t ")) {
                val status = r.text.substring(t.length + 1)
                if (status.startsWith("OK", ignoreCase = true)) return out
                throw ImapError(status.replace(Regex("^(NO|BAD)\\s*(\\[[^]]*]\\s*)?", RegexOption.IGNORE_CASE), "").ifBlank { status })
            }
            out += r
        }
    }

    private fun send(s: String) {
        output.write(s.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray())
        output.flush()
    }

    private val literalAtEnd = Regex(LITERAL_SIZE_AT_END)

    internal fun readResponse(): Response {
        val text = StringBuilder()
        val literals = mutableListOf<ByteArray>()
        while (true) {
            val line = readLine()
            text.append(line)
            val m = literalAtEnd.find(line) ?: break
            val n = m.groupValues[1].toInt()
            literals += readExactly(n)
        }
        return Response(text.toString(), literals)
    }

    private fun readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (buf.size() == 0) throw ImapError("The mail server closed the connection")
                break
            }
            if (b == '\n'.code) break
            buf.write(b)
        }
        return buf.toString("UTF-8").trimEnd('\r')
    }

    private fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r == -1) throw ImapError("The mail server closed the connection mid-message")
            off += r
        }
        return out
    }
}

/** Just enough MIME to turn a raw message into readable text. */
internal object Mime {
    class Part(val headers: Map<String, String>, val text: String, val attachments: List<String>)

    /** Header names lowercased, folded lines joined; the first of each wins. */
    fun headers(raw: ByteArray): Map<String, String> = headerBlock(String(raw, Charsets.ISO_8859_1))

    private fun headerBlock(block: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var name: String? = null
        val value = StringBuilder()
        fun flush() { name?.let { n -> if (n !in out) out[n] = value.toString().trim() } }
        for (line in block.split("\r\n", "\n")) {
            if (line.isEmpty()) break
            if ((line[0] == ' ' || line[0] == '\t') && name != null) { value.append(' ').append(line.trim()); continue }
            flush()
            val colon = line.indexOf(':')
            if (colon <= 0) { name = null; continue }
            name = line.substring(0, colon).trim().lowercase()
            value.setLength(0); value.append(line.substring(colon + 1))
        }
        flush()
        // Header values are bytes; a raw UTF-8 subject (no encoded words) should still read right.
        return out.mapValues { (_, v) -> recode(v) }
    }

    /** Text read as Latin-1 that was really UTF-8, read again as UTF-8. */
    private fun recode(s: String): String {
        if (s.all { it.code < 128 }) return s
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        val utf = String(bytes, Charsets.UTF_8)
        return if ('\uFFFD' in utf) s else utf
    }

    fun parse(raw: ByteArray): Part = part(raw, 0)

    private fun part(raw: ByteArray, depth: Int): Part {
        val split = bodyStart(raw)
        val h = headerBlock(String(raw, 0, split.first, Charsets.ISO_8859_1))
        val body = raw.copyOfRange(split.second.coerceAtMost(raw.size), raw.size)
        val type = h["content-type"].orEmpty().ifBlank { "text/plain" }
        val mime = type.substringBefore(';').trim().lowercase()
        val disposition = h["content-disposition"].orEmpty()
        val filename = param(disposition, "filename") ?: param(type, "name")
        if (mime.startsWith("multipart/") && depth < 12) {
            val boundary = param(type, "boundary") ?: return Part(h, "", emptyList())
            val parts = splitMultipart(body, boundary).map { part(it, depth + 1) }
            val attachments = parts.flatMap { it.attachments }
            val text = if (mime == "multipart/alternative") {
                // Last is richest; prefer plain text when there is some.
                parts.firstOrNull { it.headers["content-type"].orEmpty().lowercase().startsWith("text/plain") && it.text.isNotBlank() }?.text
                    ?: parts.lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
            } else parts.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
            return Part(h, text, attachments)
        }
        if (mime == "message/rfc822" && depth < 12) {
            val inner = part(decode(body, h["content-transfer-encoding"]), depth + 1)
            val head = listOfNotNull(
                inner.headers["from"]?.let { "From: ${decodeWords(it)}" },
                inner.headers["subject"]?.let { "Subject: ${decodeWords(it)}" },
            ).joinToString("\n")
            return Part(h, "---------- Forwarded message ----------\n$head\n\n${inner.text}", inner.attachments)
        }
        val isAttachment = disposition.trim().lowercase().startsWith("attachment") || (filename != null && !mime.startsWith("text/"))
        if (isAttachment || !mime.startsWith("text/")) {
            val name = filename?.let { decodeWords(it) } ?: mime
            return Part(h, "", listOf(name))
        }
        val bytes = decode(body, h["content-transfer-encoding"])
        val cs = param(type, "charset")?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val text = String(bytes, cs)
        return Part(h, if (mime == "text/html") htmlToText(text) else text.replace("\r\n", "\n").trim(), emptyList())
    }

    /** Where the headers end and the body starts. */
    private fun bodyStart(raw: ByteArray): Pair<Int, Int> {
        // A part with no headers (text/plain by default) starts with its blank line.
        if (raw.size >= 2 && raw[0] == '\r'.code.toByte() && raw[1] == '\n'.code.toByte()) return 0 to 2
        if (raw.isNotEmpty() && raw[0] == '\n'.code.toByte()) return 0 to 1
        for (i in 0 until raw.size - 1) {
            if (raw[i] == '\n'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) return i to i + 2
            if (i + 3 < raw.size && raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() && raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()) return i to i + 4
        }
        return raw.size to raw.size
    }

    private fun splitMultipart(body: ByteArray, boundary: String): List<ByteArray> {
        val s = String(body, Charsets.ISO_8859_1)
        val delim = "--$boundary"
        val out = mutableListOf<ByteArray>()
        var i = s.indexOf(delim)
        while (i >= 0) {
            var start = i + delim.length
            if (s.startsWith("--", start)) break
            start = s.indexOf('\n', start).let { if (it < 0) return out else it + 1 }
            val next = s.indexOf("\n$delim", start).let { if (it < 0) s.length else it }
            val end = if (next > start && s[next - 1] == '\r') next - 1 else next
            out += s.substring(start, end.coerceAtLeast(start)).toByteArray(Charsets.ISO_8859_1)
            i = if (next >= s.length) -1 else next + 1
        }
        return out
    }

    fun decode(body: ByteArray, encoding: String?): ByteArray = when (encoding?.trim()?.lowercase()) {
        "base64" -> runCatching { Base64.getMimeDecoder().decode(body) }.getOrDefault(body)
        "quoted-printable" -> quotedPrintable(body, header = false)
        else -> body
    }

    private fun hex(b: Int): Int = when (b) {
        in '0'.code..'9'.code -> b - '0'.code
        in 'a'.code..'f'.code -> b - 'a'.code + 10
        in 'A'.code..'F'.code -> b - 'A'.code + 10
        else -> -1
    }

    private fun quotedPrintable(b: ByteArray, header: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            when {
                header && c == '_'.code -> { out.write(' '.code); i++ }
                c == '='.code && i + 1 < b.size && (b[i + 1] == '\n'.code.toByte()) -> i += 2
                c == '='.code && i + 2 < b.size && b[i + 1] == '\r'.code.toByte() && b[i + 2] == '\n'.code.toByte() -> i += 3
                c == '='.code && i + 2 < b.size && hex(b[i + 1].toInt()) >= 0 && hex(b[i + 2].toInt()) >= 0 -> {
                    out.write(hex(b[i + 1].toInt()) * 16 + hex(b[i + 2].toInt())); i += 3
                }
                else -> { out.write(c); i++ }
            }
        }
        return out.toByteArray()
    }

    /** A parameter of a header like Content-Type, quoted or not, RFC 2231 forms included. */
    fun param(header: String, name: String): String? {
        val parts = header.split(';').drop(1).map { it.trim() }
        parts.firstOrNull { it.lowercase().startsWith("$name=") }?.let { return it.substringAfter('=').trim().trim('"') }
        // name*=utf-8''caf%C3%A9.pdf
        parts.firstOrNull { it.lowercase().startsWith("$name*=") }?.let { p ->
            val v = p.substringAfter('=').trim().trim('"')
            val cs = v.substringBefore('\'', "utf-8")
            val enc = v.substringAfterLast('\'')
            return runCatching { java.net.URLDecoder.decode(enc.replace("+", "%2B"), cs) }.getOrDefault(enc)
        }
        // name*0=...; name*1=...
        val pieces = parts.filter { it.lowercase().matches(Regex("${Regex.escape(name)}\\*\\d+\\*?=.*")) }
        if (pieces.isNotEmpty()) return pieces.joinToString("") { it.substringAfter('=').trim().trim('"') }
        return null
    }

    private val encodedWord = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")

    /** =?UTF-8?B?...?= and =?iso-8859-1?Q?...?= words in a header, decoded. */
    fun decodeWords(s: String): String {
        if ("=?" !in s) return s.trim()
        // Whitespace between two encoded words is not part of the text.
        val joined = s.replace(Regex("(\\?=)\\s+(=\\?)"), "$1$2")
        return encodedWord.replace(joined) { m ->
            val cs = runCatching { Charset.forName(m.groupValues[1].substringBefore('*')) }.getOrDefault(Charsets.UTF_8)
            val data = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("B", true)) runCatching { Base64.getDecoder().decode(data.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }) }.getOrDefault(data.toByteArray())
            else quotedPrintable(data.toByteArray(Charsets.ISO_8859_1), header = true)
            String(bytes, cs)
        }.trim()
    }

    private val entities = mapOf("nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "#39" to "'", "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©", "zwnj" to "", "zwj" to "")

    /** Mail HTML as plain text: blocks become lines, links keep their address. */
    fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(head|style|script|title)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?is)<a\\s[^>]*href\\s*=\\s*[\"']?(https?://[^\"'\\s>]+)[^>]*>(.*?)</a>")) { m ->
            val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val url = m.groupValues[1]
            if (label.isEmpty()) "" else if (label == url) url else "$label ($url)"
        }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|tr|h[1-6]|li|table|blockquote)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ")
            .replace(Regex("<[^>]+>"), "")
        s = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                // A malformed number (past U+10FFFF) stays as written rather than failing the whole message.
                e.startsWith("#x", true) -> e.drop(2).toIntOrNull(16)?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: entities[e] ?: m.value
                else -> entities[e.lowercase()] ?: m.value
            }
        }
        return s.lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
