package dev.gbot.app.local

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
                    MockResponse.Builder().code(206).body(Buffer().write(model.copyOfRange(from, model.size))).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun entry() = LocalModels.Entry(
        label = "Test model", file = "test-model.litertlm",
        url = server.url("/model.litertlm").toString(), bytes = model.size.toLong(), note = "",
    )

    @Test
    fun `a fresh download saves the whole model`() = runBlocking {
        val file = LocalModels.downloadTo(dir, entry()) { _, _ -> }

        assertArrayEquals(model, file.readBytes())
        assertFalse(File(dir, "test-model.litertlm.part").exists())
        assertEquals(null, requests.single().headers["Range"])
    }

    @Test
    fun `pausing keeps the partial file and resuming continues from it`() = runBlocking {
        // Pause by cancelling once more than half the model has arrived.
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            LocalModels.downloadTo(dir, entry()) { done, _ -> if (done > model.size / 2) job.cancel() }
        }
        job.start()
        job.join()

        val part = File(dir, "test-model.litertlm.part")
        assertTrue("partial file should be kept", part.exists())
        val saved = part.length()
        assertTrue("paused partway, saved $saved", saved > model.size / 2 && saved < model.size)
        assertFalse(File(dir, "test-model.litertlm").exists())

        // Resume: the request asks only for the missing bytes and the file finishes whole.
        val file = LocalModels.downloadTo(dir, entry()) { _, _ -> }

        assertEquals("bytes=$saved-", requests.last().headers["Range"])
        assertArrayEquals(model, file.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun `a saved part that is longer than the model is removed, not installed`() = runBlocking {
        // The server has nothing past a part this long, so it answers 416.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(416).build()
        }
        val part = File(dir, "test-model.litertlm.part")
        part.writeBytes(ByteArray(model.size + 10))

        val failure = runCatching { LocalModels.downloadTo(dir, entry()) { _, _ -> } }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(part.exists())
        assertFalse(File(dir, "test-model.litertlm").exists())
    }
}
