package dev.gbot.app.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalModelsTest {
    @get:Rule val folder = TemporaryFolder()

    private val model = ByteArray(200_000) { (it % 251).toByte() }
    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = folder.newFolder("models")
        requests.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val range = request.headers["Range"]
                return if (range == null) {
                    MockResponse.Builder().code(200).body(Buffer().write(model)).build()
                } else {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    MockResponse.Builder().code(206)
                        .addHeader("Content-Range", "bytes $from-${model.size - 1}/${model.size}")
                        .body(Buffer().write(model.copyOfRange(from, model.size)))
                        .build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun entry(bytes: Long = model.size.toLong()) = LocalModels.Entry(
        label = "Test model", file = "test-model.litertlm",
        url = server.url("/model.litertlm").toString(), bytes = bytes, note = "",
    )

    /** Pauses by cancelling once more than half the model has arrived. Returns the bytes saved. */
    private fun pauseHalfway(e: LocalModels.Entry): Long = runBlocking {
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            LocalModels.downloadTo(dir, e) { done, _ -> if (done > model.size / 2) job.cancel() }
        }
        job.start()
        job.join()
        File(dir, "test-model.litertlm.part").length()
    }

    @Test
    fun `a fresh download saves the whole model`() = runBlocking {
        val file = LocalModels.downloadTo(dir, entry()) { _, _ -> }

        assertArrayEquals(model, file.readBytes())
        assertFalse(File(dir, "test-model.litertlm.part").exists())
        assertEquals(null, requests.single().headers["Range"])
    }

    @Test
    fun `pausing keeps the partial file and resuming continues from it`() = runBlocking {
        val saved = pauseHalfway(entry())

        val part = File(dir, "test-model.litertlm.part")
        assertTrue("paused partway, saved $saved", saved > model.size / 2 && saved < model.size)
        assertFalse(File(dir, "test-model.litertlm").exists())

        // Resume: the request asks only for the missing bytes and the file finishes whole.
        val file = LocalModels.downloadTo(dir, entry()) { _, _ -> }

        assertEquals("bytes=$saved-", requests.last().headers["Range"])
        assertArrayEquals(model, file.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun `pausing while the connection is stalled reports a cancel, not a network error`() = runBlocking {
        // The body trickles in, so the download is blocked on a read when it is paused.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).body(Buffer().write(model))
                    .throttleBody(8_192, 200, TimeUnit.MILLISECONDS).build()
        }
        val firstBytes = CountDownLatch(1)
        val download = async(Dispatchers.IO) {
            LocalModels.downloadTo(dir, entry()) { done, _ -> if (done > 0) firstBytes.countDown() }
        }
        assertTrue(firstBytes.await(10, TimeUnit.SECONDS))

        download.cancel()
        val failure = runCatching { runBlocking { download.await() } }.exceptionOrNull()

        assertTrue("pause should look like a cancel, got $failure", failure is CancellationException)
        assertTrue(File(dir, "test-model.litertlm.part").exists())
    }

    @Test
    fun `resuming uses the server's size when the catalog size is wrong`() = runBlocking {
        val wrong = entry(bytes = model.size + 1_000L)
        val saved = pauseHalfway(wrong)
        assertTrue(saved > 0)

        val file = LocalModels.downloadTo(dir, wrong) { _, _ -> }

        assertArrayEquals(model, file.readBytes())
    }

    @Test
    fun `a complete saved part is installed even when the catalog size is wrong`() = runBlocking {
        // The part is the whole file, but the server sends no Content-Range on its 416.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.headers["Range"] != null) MockResponse.Builder().code(416).build()
                else MockResponse.Builder().code(200).body(Buffer().write(model)).build()
        }
        File(dir, "test-model.litertlm.part").writeBytes(model)

        val file = LocalModels.downloadTo(dir, entry(bytes = model.size + 1_000L)) { _, _ -> }

        assertArrayEquals(model, file.readBytes())
        assertFalse(File(dir, "test-model.litertlm.part").exists())
    }

    @Test
    fun `a failed size check keeps the saved part for a retry`() = runBlocking {
        // A 416 with no Content-Range, then the size check itself fails with a server error.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "HEAD") MockResponse.Builder().code(503).addHeader("Content-Length", "42").build()
                else MockResponse.Builder().code(416).build()
        }
        val part = File(dir, "test-model.litertlm.part")
        part.writeBytes(model)

        val failure = runCatching { LocalModels.downloadTo(dir, entry()) { _, _ -> } }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(part.exists())
        assertEquals(model.size.toLong(), part.length())
    }

    @Test
    fun `a saved part that is longer than the model is removed, not installed`() = runBlocking {
        // The server has nothing past a part this long, so it answers 416.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */${model.size}").build()
        }
        val part = File(dir, "test-model.litertlm.part")
        part.writeBytes(ByteArray(model.size + 10))

        val failure = runCatching { LocalModels.downloadTo(dir, entry()) { _, _ -> } }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(part.exists())
        assertFalse(File(dir, "test-model.litertlm").exists())
    }
}
