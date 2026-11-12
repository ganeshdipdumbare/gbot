package dev.gbot.app

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as TtsVoice
import dev.gbot.app.net.Api
import dev.gbot.app.net.VoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Voice, as on the web: dictation goes to the server's own speech-to-text
 * when it has one, otherwise to the phone's recognizer; reading aloud uses
 * the server's voice when set up, otherwise the phone's voices.
 */
class Voice(private val context: Context) {
    private val api: Api get() = GbotApp.instance.api
    private var info: VoiceInfo? = null
    suspend fun info(): VoiceInfo = info ?: runCatching { api.voice() }.getOrDefault(VoiceInfo()).also { info = it }

    private var tts: TextToSpeech? = null
    private var ttsReady = CompletableDeferred<Boolean>()
    private var player: MediaPlayer? = null

    private fun ensureTts(): TextToSpeech {
        tts?.let { return it }
        ttsReady = CompletableDeferred()
        return TextToSpeech(context) { ttsReady.complete(it == TextToSpeech.SUCCESS) }.also { tts = it }
    }

    /** The phone's voices for your language, on-device ones first. */
    suspend fun systemVoices(): List<TtsVoice> {
        val t = ensureTts()
        if (!ttsReady.await()) return emptyList()
        val lang = Locale.getDefault().language
        return runCatching { t.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == lang }
            .sortedWith(compareBy<TtsVoice>({ it.isNetworkConnectionRequired }, { it.name }))
    }

    suspend fun speak(text: String, voice: String?) {
        stop()
        val t = plain(text).take(4000)
        if (t.isEmpty()) return
        if (info().speak) {
            val bytes = api.speak(t, voice?.takeIf { it.isNotEmpty() })
            val f = withContext(Dispatchers.IO) { File.createTempFile("speak", ".audio", context.cacheDir).apply { writeBytes(bytes); deleteOnExit() } }
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { it.release(); f.delete(); if (player === it) player = null }
                prepare()
                start()
            }
            return
        }
        val engine = ensureTts()
        if (!ttsReady.await()) throw IllegalStateException("No voice is available on this phone")
        if (!voice.isNullOrEmpty()) runCatching { engine.voices.find { it.name == voice }?.let { engine.voice = it } }
        engine.setSpeechRate(1.03f)
        engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "gbot")
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        tts?.stop()
    }

    companion object {
        fun plain(md: String) = md
            .replace(Regex("```[\\s\\S]*?```"), " (code) ")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("[*_#>|]"), "")
            .trim()
    }
}

/** One dictation: start, then stop to get the words. */
class Dictation(private val context: Context, private val voice: Voice, private val api: Api, private val onText: (String) -> Unit) {
    enum class Mode { Server, Phone, None }
    enum class St { Idle, Listening, Working }

    private val _state = MutableStateFlow(St.Idle)
    val state: StateFlow<St> = _state
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    var mode = Mode.None; private set
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var recognizer: SpeechRecognizer? = null

    suspend fun prepare(): Mode {
        mode = when {
            voice.info().transcribe -> Mode.Server
            SpeechRecognizer.isRecognitionAvailable(context) -> Mode.Phone
            else -> Mode.None
        }
        return mode
    }

    fun start() {
        when (mode) {
            Mode.Server -> {
                val f = File.createTempFile("dictation", ".m4a", context.cacheDir)
                val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(16000)
                r.setAudioEncodingBitRate(64000)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                file = f
                _state.value = St.Listening
            }
            Mode.Phone -> {
                val sr = SpeechRecognizer.createSpeechRecognizer(context)
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
                        finishPhone()
                    }
                    override fun onError(error: Int) = finishPhone()
                    override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { _state.value = St.Working }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
                recognizer = sr
                _state.value = St.Listening
            }
            Mode.None -> {}
        }
    }

    /** Recording level for the mic button, 0..1; poll while listening. */
    fun sample() {
        recorder?.let { r -> _level.value = (runCatching { r.maxAmplitude }.getOrDefault(0) / 12000f).coerceIn(0f, 1f) }
    }

    suspend fun stop() {
        recognizer?.let { it.stopListening(); _state.value = St.Working; return }
        val r = recorder ?: return
        val f = file
        recorder = null
        runCatching { r.stop() }
        r.release()
        _level.value = 0f
        _state.value = St.Working
        try {
            if (f != null && f.length() > 0) {
                val text = api.transcribe(withContext(Dispatchers.IO) { f.readBytes() }, "audio/mp4")
                if (text.isNotBlank()) onText(text)
            }
        } finally {
            f?.delete()
            _state.value = St.Idle
        }
    }

    private fun finishPhone() {
        recognizer?.destroy()
        recognizer = null
        _level.value = 0f
        _state.value = St.Idle
    }

    fun cancel() {
        recorder?.runCatching { stop(); release() }
        recorder = null
        file?.delete()
        finishPhone()
    }
}
