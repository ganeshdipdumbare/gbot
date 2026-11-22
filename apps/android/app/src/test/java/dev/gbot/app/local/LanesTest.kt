package dev.gbot.app.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class LanesTest {
    @Test
    fun `two chats run side by side while one chat stays in order`(): Unit = runBlocking {
        val lanes = Lanes()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        // pip is busy until the gate opens; its next message waits behind it.
        val first = launch(Dispatchers.Default) { lanes.run("pip") { order.add("pip 1"); gate.await() } }
        delay(50)
        val second = launch(Dispatchers.Default) { lanes.run("pip") { order.add("pip 2") } }
        delay(50)
        // mo isn't held up by pip.
        withTimeout(2000) { lanes.run("mo") { order.add("mo 1") } }
        assertEquals(listOf("pip 1", "mo 1"), order.toList())
        gate.complete(Unit)
        withTimeout(5000) { first.join(); second.join() }
        assertEquals(listOf("pip 1", "mo 1", "pip 2"), order.toList())
    }

    @Test
    fun `holders of the same model share it, another model waits until it is free`(): Unit = runBlocking {
        val opened = Collections.synchronizedList(mutableListOf<String>())
        val closed = Collections.synchronizedList(mutableListOf<String>())
        val model = Leased<String>(open = { opened.add(it); it }, close = { closed.add(it) })
        val gate = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) {
            model.hold("small") {
                // A nested hold of the same model, like task_verify inside a run.
                model.hold("small") { }
                gate.await()
            }
        }
        delay(50)
        withTimeout(2000) { model.hold("small") { assertEquals("small", it) } }
        val b = async(Dispatchers.Default) { model.hold("big") { it } }
        delay(100)
        assertFalse("big waits while small is held", b.isCompleted)
        assertFalse("can't close while held", model.closeIfFree())
        gate.complete(Unit)
        withTimeout(5000) { a.join(); assertEquals("big", b.await()) }
        assertEquals(listOf("small", "big"), opened.toList())
        assertEquals(listOf("small"), closed.toList())
        assertTrue(model.closeIfFree())
        assertFalse(model.isOpen)
        assertEquals(listOf("small", "big"), closed.toList())
    }

    @Test
    fun `a run waiting for another model is not passed by runs on the open one`(): Unit = runBlocking {
        val model = Leased<String>(open = { it }, close = {})
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) { model.hold("small") { gate.await() } }
        delay(50)
        val big = launch(Dispatchers.Default) { model.hold("big") { order.add("big") } }
        delay(50)
        // small is open, but big is first in line, so this one waits behind it.
        val small = launch(Dispatchers.Default) { model.hold("small") { order.add("small") } }
        delay(100)
        assertTrue(order.isEmpty())
        gate.complete(Unit)
        withTimeout(5000) { a.join(); big.join(); small.join() }
        assertEquals(listOf("big", "small"), order.toList())
    }

    @Test
    fun `past the limit the person goes first, and a nested hold never waits`(): Unit = runBlocking {
        val model = Leased<String>(open = { it }, close = {}, limit = 1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        var waited = 0
        val a = launch(Dispatchers.Default) {
            model.hold("small") {
                gate.await()
                // task_verify inside the run: same model, no second slot.
                withTimeout(1000) { model.hold("small") { order.add("verify") } }
            }
        }
        delay(50)
        val bots = launch(Dispatchers.Default) { model.hold("small", Priority.BOTS, onWait = { waited++ }) { order.add("bots") } }
        delay(30)
        val person = launch(Dispatchers.Default) { model.hold("small", Priority.PERSON, onWait = { waited++ }) { order.add("person") } }
        delay(50)
        assertEquals(2, waited)
        gate.complete(Unit)
        withTimeout(5000) { a.join(); bots.join(); person.join() }
        assertEquals(listOf("verify", "person", "bots"), order.toList())
    }

    @Test
    fun `a run cancelled while waiting for the model gives up its place`(): Unit = runBlocking {
        val model = Leased<String>(open = { it }, close = {}, limit = 1)
        val gate = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) { model.hold("small") { gate.await() } }
        delay(50)
        val waiting = launch(Dispatchers.Default) { model.hold("big") { error("should not run") } }
        delay(50)
        waiting.cancel()
        waiting.join()
        gate.complete(Unit)
        withTimeout(5000) { a.join() }
        withTimeout(1000) { model.hold("small") { assertEquals("small", it) } }
        assertTrue(model.closeIfFree())
    }

    @Test
    fun `a bot's runs are counted so the last one to end clears it`() {
        val runs = Counts()
        assertEquals(1, runs.up("pip"))
        assertEquals(2, runs.up("pip"))
        assertEquals(1, runs.down("pip"))
        assertEquals(1, runs.of("pip"))
        assertEquals(0, runs.down("pip"))
        assertEquals(0, runs.down("pip"))
        assertEquals(0, runs.of("pip"))
    }

    @Test
    fun `a holder waiting on the person frees its place but keeps the model open`(): Unit = runBlocking {
        val closed = Collections.synchronizedList(mutableListOf<String>())
        val model = Leased<String>(open = { it }, close = { closed.add(it) }, limit = 1)
        val answer = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val a = launch(Dispatchers.Default) {
            model.hold("small") {
                model.aside { answer.await() }
                order.add("a back")
            }
        }
        delay(50)
        // Another run on the same model gets the place while a waits on the person.
        withTimeout(1000) { model.hold("small") { order.add("b") } }
        // A different model can't swap it out from under a's open conversation.
        val big = launch(Dispatchers.Default) { model.hold("big") { order.add("big") } }
        delay(100)
        assertFalse(model.closeIfFree())
        assertTrue(closed.isEmpty())
        answer.complete(Unit)
        withTimeout(5000) { a.join(); big.join() }
        assertEquals(listOf("b", "a back", "big"), order.toList())
        assertEquals(listOf("small"), closed.toList())
    }

    @Test
    fun `coming back from a wait goes ahead of the line`(): Unit = runBlocking {
        val model = Leased<String>(open = { it }, close = {}, limit = 1)
        val answer = CompletableDeferred<Unit>()
        val busy = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val a = launch(Dispatchers.Default) { model.hold("small") { model.aside { answer.await() }; order.add("a") } }
        delay(50)
        val b = launch(Dispatchers.Default) { model.hold("small") { busy.await(); order.add("b") } }
        delay(50)
        val c = launch(Dispatchers.Default) { model.hold("small", Priority.PERSON) { order.add("c") } }
        delay(50)
        answer.complete(Unit)
        delay(50)
        busy.complete(Unit)
        withTimeout(5000) { a.join(); b.join(); c.join() }
        assertEquals(listOf("b", "a", "c"), order.toList())
    }

    @Test
    fun `a run stopped while it waits on the person lets go at once`(): Unit = runBlocking {
        val model = Leased<String>(open = { it }, close = {}, limit = 1)
        val busy = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) { model.hold("small") { model.aside { CompletableDeferred<Unit>().await() } } }
        delay(50)
        // b takes the only place while a waits on the person.
        val b = launch(Dispatchers.Default) { model.hold("small") { busy.await() } }
        delay(50)
        a.cancel()
        // a doesn't queue to come back behind b: it ends now.
        withTimeout(1000) { a.join() }
        busy.complete(Unit)
        withTimeout(5000) { b.join() }
        assertTrue("nothing is left counted", model.closeIfFree())
    }

    @Test
    fun `a run served the moment it joins the line shows no wait`(): Unit = runBlocking {
        val model = Leased<String>(open = { it }, close = {}, limit = 2)
        val gate = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) { model.hold("small") { gate.await() } }
        delay(50)
        val big = launch(Dispatchers.Default) { model.hold("big", Priority.BOTS) { } }
        delay(50)
        var waited = false
        // The person outranks the head, and small has a free place.
        withTimeout(1000) { model.hold("small", Priority.PERSON, onWait = { waited = true }) { } }
        assertFalse(waited)
        gate.complete(Unit)
        withTimeout(5000) { a.join(); big.join() }
    }

    @Test
    fun `runs go past another model that waits only on a bot waiting for the person`(): Unit = runBlocking {
        val model = Leased<String>(open = { it }, close = {}, limit = 2)
        val answer = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val a = launch(Dispatchers.Default) { model.hold("small") { model.aside { answer.await() }; order.add("a") } }
        delay(50)
        val big = launch(Dispatchers.Default) { model.hold("big") { order.add("big") } }
        delay(50)
        // Nobody is working on small, only a waiting on the person: this one goes ahead.
        withTimeout(1000) { model.hold("small", Priority.PERSON) { order.add("person") } }
        answer.complete(Unit)
        withTimeout(5000) { a.join(); big.join() }
        assertEquals(listOf("person", "a", "big"), order.toList())
    }

    @Test
    fun `runs past a stuck head fill every place on the open model`(): Unit = runBlocking {
        val model = Leased<String>(open = { it }, close = {}, limit = 2)
        val answer = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) { model.hold("small") { model.aside { answer.await() } } }
        delay(50)
        val big = launch(Dispatchers.Default) { model.hold("big") { } }
        delay(50)
        // p goes past the head and keeps working; q still finds the second place.
        val p = launch(Dispatchers.Default) { model.hold("small", Priority.PERSON) { gate.await() } }
        delay(50)
        withTimeout(1000) { model.hold("small", Priority.PERSON) { } }
        gate.complete(Unit)
        answer.complete(Unit)
        withTimeout(5000) { a.join(); p.join(); big.join() }
    }
}
