package dev.gbot.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.local.Answer
import dev.gbot.app.local.AskBook
import dev.gbot.app.local.LocalDb
import dev.gbot.app.local.MAX_ASKS_PER_ROOT
import dev.gbot.app.local.MAX_ASK_DEPTH
import dev.gbot.app.local.Priority
import dev.gbot.app.local.Turns
import dev.gbot.app.local.Work
import dev.gbot.app.local.canMessage
import dev.gbot.app.local.exchangeData
import dev.gbot.app.local.joinPrompt
import dev.gbot.app.local.roundDone
import dev.gbot.app.net.Message
import dev.gbot.app.net.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** Bots asking bots: the phone's half of docs/protocol/bot-asks.md. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AsksTest {
    // The same cases the server's tests read: one protocol, two runtimes.
    private val fixtures: JsonObject by lazy {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/protocol/bot-asks.fixtures.json") }
            .first { it.exists() }
        json.parseToJsonElement(f.readText()).jsonObject
    }

    @Test
    fun `the limits match the server's`() {
        val limits = fixtures["limits"]!!.jsonObject
        assertEquals(MAX_ASK_DEPTH, limits["maxDepth"]!!.jsonPrimitive.int)
        assertEquals(MAX_ASKS_PER_ROOT, limits["maxPerRoot"]!!.jsonPrimitive.int)
        for (c in fixtures["canMessage"]!!.jsonArray.map { it.jsonObject }) {
            val depth = c["depth"]!!.jsonPrimitive.int
            val sent = c["sent"]!!.jsonPrimitive.int
            assertEquals("canMessage($depth, $sent)", c["ok"]!!.jsonPrimitive.boolean, canMessage(depth, sent))
        }
    }

    @Test
    fun `a round is done when every ask has ended`() {
        for (c in fixtures["roundDone"]!!.jsonArray.map { it.jsonObject }) {
            val statuses = c["statuses"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(statuses.toString(), c["done"]!!.jsonPrimitive.boolean, roundDone(statuses))
        }
    }

    @Test
    fun `a round's answers go back in the same words as on the server`() {
        for (c in fixtures["joinPrompt"]!!.jsonArray.map { it.jsonObject }) {
            val answers = c["answers"]!!.jsonArray.map { a ->
                val o = a.jsonObject
                fun s(k: String) = o[k]!!.jsonPrimitive.content
                Answer(s("name"), s("message"), s("status"), s("reply"))
            }
            assertEquals(c["name"]!!.jsonPrimitive.content, c["prompt"]!!.jsonPrimitive.content, joinPrompt(answers))
        }
    }

    @Test
    fun `a request's messages are counted wherever they happen`() {
        val book = AskBook()
        book.startRun("rt_1", 0)
        repeat(MAX_ASKS_PER_ROOT) { i -> assertTrue("message ${i + 1}", book.take("rt_1", i % MAX_ASK_DEPTH)) }
        assertFalse(book.take("rt_1", 0))
        assertFalse("the deepest bot can't send", book.take("rt_2", MAX_ASK_DEPTH))
        book.call("rt_1"); book.call("rt_1")
        assertTrue(book.traceLine("rt_1").contains("asks=6"))
        assertTrue(book.traceLine("rt_1").contains("calls=2"))
    }

    @Test
    fun `a round is handed back once`() {
        val book = AskBook()
        book.open("rd_1")
        assertTrue(book.isOpen("rd_1"))
        book.close("rd_1")
        assertTrue(book.claimJoin("rd_1"))
        assertFalse(book.claimJoin("rd_1"))
        assertTrue(book.wasJoined("rd_1"))
    }

    @Test
    fun `the person goes first, then answers, then bots`(): Unit = runBlocking {
        val turns = Turns()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        // Something is running; three runs line up behind it.
        val first = launch(Dispatchers.Default) { turns.take(Priority.PERSON) { gate.await() } }
        delay(50)
        val runs = listOf("ask" to Priority.BOTS, "join" to Priority.ANSWERS, "person" to Priority.PERSON).map { (name, p) ->
            async(Dispatchers.Default) { turns.take(p) { order.add(name) } }.also { delay(30) }
        }
        assertFalse("the turn is taken", turns.tryTake())
        gate.complete(Unit)
        withTimeout(5000) { first.join(); runs.forEach { it.await() } }
        assertEquals(listOf("person", "join", "ask"), order)
        assertTrue("the turn is free again", turns.tryTake())
        turns.release()
    }

    @Test
    fun `a run cancelled while waiting gives up its place`(): Unit = runBlocking {
        val turns = Turns()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) { turns.take(Priority.PERSON) { gate.await() } }
        delay(50)
        val waiting = launch(Dispatchers.Default) { turns.take(Priority.PERSON) { error("should not run") } }
        delay(50)
        waiting.cancel()
        waiting.join()
        gate.complete(Unit)
        withTimeout(5000) { first.join() }
        assertTrue(turns.tryTake())
        turns.release()
    }

    @Test
    fun `exchange cards are found by round and ask, and queued runs survive`() {
        val db = LocalDb(ApplicationProvider.getApplicationContext())
        fun card(id: String, bot: String, data: JsonObject, at: String) =
            Message(id = id, botId = bot, role = "event", content = "", createdAt = at, data = data)
        val a = exchangeData("pip", "taro", "Name a fruit.", ask = "ak_1", round = "rd_1", root = "rt_1")
        val b = exchangeData("pip", "mo", "Name a colour.", ask = "ak_2", round = "rd_1", root = "rt_1")
        val other = exchangeData("pip", "mo", "Tell only.", ask = "ak_3", expect = "tell")
        db.putMessage(card("m1", "pip", a, "2026-11-12T10:00:00Z"))
        db.putMessage(card("m2", "taro", a, "2026-11-12T10:00:01Z"))
        db.putMessage(card("m3", "pip", b, "2026-11-12T10:00:02Z"))
        db.putMessage(card("m4", "mo", b, "2026-11-12T10:00:03Z"))
        db.putMessage(card("m5", "pip", other, "2026-11-12T10:00:04Z"))
        assertEquals(listOf("m1", "m2", "m3", "m4"), db.exchangeCards("round", "rd_1").map { it.id })
        assertEquals(listOf("m1", "m2"), db.exchangeCards("ask", "ak_1").map { it.id })
        assertEquals(5, db.recentExchangeCards("2026-11-11T00:00:00Z").size)
        assertEquals("1", db.message("m2")!!.str("depth"))

        val w = Work("pip", "hello", root = "rt_1")
        db.putQueued("q_1", "2026-11-12T10:00:00Z", json.encodeToString(Work.serializer(), w))
        val back = db.queued().single()
        assertEquals("q_1", back.first)
        assertEquals(w, json.decodeFromString(Work.serializer(), back.second))
        db.removeQueued("q_1")
        assertTrue(db.queued().isEmpty())
    }

    @Test
    fun `only the person's runs plan, and bots' runs wait behind them`() {
        assertTrue(Work("a", "x").plans)
        assertFalse(Work("a", "x", kind = "ask").plans)
        assertFalse(Work("a", "x", kind = "join").plans)
        assertTrue(Work("a", "x", kind = "group").durable)
        assertFalse(Work("a", "x", kind = "tell").durable)
        assertTrue(Work("a", "x").priority < Work("a", "x", kind = "join").priority)
        assertTrue(Work("a", "x", kind = "join").priority < Work("a", "x", kind = "ask").priority)
    }
}
