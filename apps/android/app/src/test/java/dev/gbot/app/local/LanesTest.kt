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
}
