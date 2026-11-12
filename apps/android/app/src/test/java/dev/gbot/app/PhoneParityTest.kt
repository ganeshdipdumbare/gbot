package dev.gbot.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.local.BrowserController
import dev.gbot.app.local.Contract
import dev.gbot.app.local.LocalDb
import dev.gbot.app.local.LocalSlack
import dev.gbot.app.local.LocalTools
import dev.gbot.app.local.RunBook
import dev.gbot.app.local.SlackEvent
import dev.gbot.app.local.SlackTarget
import dev.gbot.app.local.receipt
import dev.gbot.app.local.verifyPrompt
import dev.gbot.app.net.SlackInstall
import dev.gbot.app.net.SlackLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What phone mode does that used to need the server: Slack, checking work, a bot's own files. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhoneParityTest {
    @Test
    fun `slack events are read like the server reads them`() {
        val e = SlackEvent(type = "app_mention", user = "U1", text = "<@U0BOT>  what's   up <@U2>", channel = "C1", ts = "1.1")
        assertEquals("what's up", e.prompt)
        assertTrue(e.actionable)
        assertEquals("1.1", e.thread)
        assertEquals("0.9", e.copy(threadTs = "0.9").thread)
        // Bots (gbot's own replies too), edits and empty mentions start nothing.
        assertFalse(e.copy(botId = "B1").actionable)
        assertFalse(e.copy(subtype = "message_changed").actionable)
        assertFalse(e.copy(user = "").actionable)
        assertFalse(e.copy(text = "<@U0BOT>").actionable)
        assertFalse(e.copy(type = "reaction_added").actionable)
    }

    @Test
    fun `slack over socket mode connects, hears a mention once, acknowledges it and replies in the thread`() {
        val server = MockWebServer()
        val acks = Collections.synchronizedList(mutableListOf<String>())
        val posts = Collections.synchronizedList(mutableListOf<String>())
        val heard = Collections.synchronizedList(mutableListOf<Pair<SlackLink, SlackEvent>>())
        val gotMention = CountDownLatch(1)
        val acked = CountDownLatch(2)
        val event = """{"type":"app_mention","user":"U1","text":"<@U0BOT> hello","channel":"C1","ts":"111.2"}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val auth = request.headers["Authorization"].orEmpty()
                fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
                return when (path) {
                    "/api/auth.test" -> if (auth == "Bearer xoxb-1") ok("""{"ok":true,"team_id":"T1","team":"Acme"}""") else ok("""{"ok":false,"error":"invalid_auth"}""")
                    "/api/apps.connections.open" -> if (auth == "Bearer xapp-1") ok("""{"ok":true,"url":"${server.url("/socket").toString().replace("http", "ws")}"}""") else ok("""{"ok":false,"error":"not_allowed_token_type"}""")
                    "/api/chat.postMessage" -> { posts.add(request.body?.utf8().orEmpty()); ok("""{"ok":true}""") }
                    "/socket" -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"hello"}""")
                            // The same message twice (app_mention, then a redelivery): answered once.
                            webSocket.send("""{"type":"events_api","envelope_id":"e1","payload":{"event_id":"Ev1","event":$event}}""")
                            webSocket.send("""{"type":"events_api","envelope_id":"e2","retry_attempt":1,"payload":{"event_id":"Ev2","event":$event}}""")
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) { acks.add(text); acked.countDown() }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    }).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            val db = LocalDb(ctx)
            val slack = LocalSlack(ctx, db, scope, { _: SlackInstall, l, e -> heard.add(l to e); gotMention.countDown() }, base = server.url("/api").toString().trimEnd('/'))

            val bad = runCatching { slack.connect("xoxb-1", "xoxb-1") }.exceptionOrNull()
            assertTrue("the app-level token is checked", bad?.message?.contains("xapp-") == true)

            // Linked before the event arrives; the link is checked when the mention lands.
            val install = slack.connect("xoxb-1", "xapp-1")
            assertEquals("T1", install.teamId)
            assertEquals("Acme", install.teamName)
            slack.link(install.id, "C1", "#general", "bot-1")

            assertTrue("the mention arrives", gotMention.await(10, TimeUnit.SECONDS))
            assertTrue("both envelopes are acknowledged", acked.await(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals(1, heard.size)
            assertEquals("hello", heard[0].second.prompt)
            assertEquals("bot-1", heard[0].first.botId)
            assertEquals(setOf("""{"envelope_id":"e1"}""", """{"envelope_id":"e2"}"""), acks.toSet())
            assertEquals("listening", slack.installs().single().status)

            slack.post(SlackTarget(install.id, "C1", "111.2"), "Hi there")
            assertTrue(posts.single().contains("\"thread_ts\":\"111.2\""))
            assertTrue(posts.single().contains("Hi there"))

            slack.disconnect(install.id)
            assertTrue(slack.installs().isEmpty())
        } finally {
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun `a checked deliverable gets the server's receipt`() {
        val ok = receipt("""```json
            {"checks":[{"check":"Has 3 flights","status":"pass","evidence":"read 3 results"},{"check":"Under 200","status":"unverified","evidence":""}],"summary":"Looks right."}
            ```""")
        assertTrue(ok.startsWith("**Verification**\n- ✅ Has 3 flights — read 3 results\n- ⚠️ Under 200"))
        assertTrue(ok.contains("Looks right."))
        assertTrue(receipt("""{"checks":[{"check":"Sent","status":"fail"}]}""").contains("do not claim the task is done"))
        assertTrue(receipt("not json").contains("Could not verify automatically"))

        val book = RunBook()
        book.add("opened https://x.dev", "page text")
        book.add("vault_type", "secret!") // never shown to the verifier
        val prompt = verifyPrompt("find flights", Contract("Three flights", listOf("Has 3 flights")), book.evidence())
        assertTrue(prompt.contains("- opened https://x.dev: page text"))
        assertFalse(prompt.contains("secret!"))
    }

    @Test
    fun `a bot's files stay in its own folder`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tools = LocalTools(ctx, LocalDb(ctx), BrowserController(ctx), "bot-files")
        val args = { path: String, content: String? -> buildJsonObject { put("path", path); if (content != null) put("content", content) } }
        val saved = tools.run("file_write", args("notes/trip.md", "Lisbon on Friday"))
        assertTrue(saved, saved.startsWith("Saved notes/trip.md"))
        assertEquals("Lisbon on Friday", tools.run("file_read", args("notes/trip.md", null)))
        assertTrue(tools.run("file_list", buildJsonObject {}).contains("notes/trip.md"))
        assertTrue(tools.run("file_write", args("../../escape.txt", "x")).startsWith("Use a plain file name"))
        assertTrue(tools.run("file_read", args("../../../shared_prefs/gbot.xml", null)).startsWith("No such file"))
    }
}
