package dev.gbot.app.live

import dev.gbot.app.data.Store
import dev.gbot.app.data.groupKey
import dev.gbot.app.data.keyOf
import dev.gbot.app.net.Bot
import dev.gbot.app.net.ComputerStatus
import dev.gbot.app.net.Group
import dev.gbot.app.net.Http
import dev.gbot.app.net.Message
import dev.gbot.app.net.PollResp
import dev.gbot.app.net.Presence
import dev.gbot.app.net.QueueItem
import dev.gbot.app.net.json
import dev.gbot.app.net.s
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * The live connection to the server: a server-sent event stream, or
 * long-polling when something on the way buffers the stream (the same
 * fallback the web app uses). One connection serves the screens and the
 * background alerts.
 */
class Live(private val http: Http, private val store: Store, private val scope: CoroutineScope, private val alerts: Alerts, private val handler: Events) {
    private var job: Job? = null
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    /** Every event, for screens that react to one (routines, read aloud). */
    val events = _events.asSharedFlow()

    val running: Boolean get() = job?.isActive == true

    @Synchronized
    fun start() {
        if (running || http.server == null) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        if (dev.gbot.app.GbotApp.instance.mode == dev.gbot.app.Mode.Server) store.setConnected(false)
    }

    fun restart() { stop(); start() }

    private suspend fun run() {
        var connectedBefore = false
        var backoff = 1500L
        while (currentCoroutineContext().isActive) {
            when (sseOnce(connectedBefore)) {
                Outcome.Buffered -> { poll(); return }
                Outcome.Ended -> { connectedBefore = true; backoff = 1500L }
                Outcome.Failed -> backoff = (backoff * 2).coerceAtMost(15_000L)
                Outcome.SignedOut -> return
            }
            store.setConnected(false)
            delay(backoff)
        }
    }

    private enum class Outcome { Ended, Failed, Buffered, SignedOut }

    private suspend fun sseOnce(catchUpOnHello: Boolean): Outcome {
        val done = CompletableDeferred<Outcome>()
        val hello = CompletableDeferred<Unit>()
        val req = http.request("/api/events").header("Accept", "text/event-stream").build()
        val source = EventSources.createFactory(http.streaming).newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val e = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                if (e.s("type") == "hello") {
                    hello.complete(Unit)
                    store.setConnected(true)
                    if (catchUpOnHello) scope.launch { catchUp() }
                    return
                }
                handle(e)
            }
            override fun onClosed(eventSource: EventSource) { done.complete(Outcome.Ended) }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                done.complete(if (response?.code == 401) Outcome.SignedOut else if (hello.isCompleted) Outcome.Ended else Outcome.Failed)
            }
        })
        try {
            // A path that holds the stream back never delivers the hello.
            val got = withTimeoutOrNull(4000) { kotlinx.coroutines.selects.select { hello.onAwait { true }; done.onAwait { false } } }
            if (got == null) { source.cancel(); return Outcome.Buffered }
            return done.await()
        } finally {
            source.cancel()
        }
    }

    private suspend fun poll() {
        var after = 0L
        while (currentCoroutineContext().isActive) {
            try {
                val r = withContext(Dispatchers.IO) {
                    json.decodeFromString<PollResp>(http.call("GET", "/api/events/poll?after=$after"))
                }
                store.setConnected(true)
                if (r.reset) catchUp()
                r.events.forEach(::handle)
                after = r.next
            } catch (e: Exception) {
                store.setConnected(false)
                delay(2000)
            }
        }
    }

    suspend fun catchUp() {
        runCatching { store.loadAll() }
        store.openChat?.let { k ->
            runCatching { store.loadMessages(k) }
            runCatching { store.loadQueue(k) }
        }
    }

    private fun handle(e: JsonObject) {
        _events.tryEmit(e)
        handler.handle(e)
    }
}
