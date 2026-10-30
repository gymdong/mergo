package app.mergo.ai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.mergo.model.VoiceIO
import app.mergo.model.YesNo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/** speak()는 TTS가 끝날 때까지 기다린다. 말하는 동안 마이크가 열려 머고 목소리를 듣지 않게 하기 위해서다. */
class AndroidVoiceIO(
    context: Context,
    private val onMicBusy: (Boolean) -> Unit = {},
) : VoiceIO {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val ready = CompletableDeferred<Boolean>()
    private val speaking = ConcurrentHashMap<String, Continuation<Unit>>()
    private val audio = app.getSystemService(AudioManager::class.java)
    private val spoken = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(spoken)
        .build()

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(spoken)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                speaking.remove(utteranceId)?.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                speaking.remove(utteranceId)?.resume(Unit)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                Log.w(TAG, "TTS 오류 코드 $errorCode")
                speaking.remove(utteranceId)?.resume(Unit)
            }
        })
    }

    override suspend fun speak(text: String) = usingMic { overMedia { say(text) } }

    /** 영상 소리에 묻히지 않도록 말하는 동안만 다른 소리를 낮추고, 끝나면 되돌린다. */
    private suspend fun overMedia(block: suspend () -> Unit) {
        val granted = audio.requestAudioFocus(focus)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "소리 낮추기 실패: $granted")
        try {
            block()
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** 디버그: 앱과 같은 엔진의 음성을 파일로 저장한다. 시연 영상에 음성만 입히는 데 쓴다. */
    fun saveSpeech(text: String, file: java.io.File) {
        tts.setLanguage(Locale.KOREAN)
        tts.synthesizeToFile(text, null, file, UUID.randomUUID().toString())
    }

    private suspend fun say(text: String) {
        val usable = withTimeoutOrNull(TTS_INIT_MS) { ready.await() } ?: false
        if (!usable) {
            Log.w(TAG, "TTS 사용 불가: $text")
            return
        }
        val language = tts.setLanguage(Locale.KOREAN)
        if (language < TextToSpeech.LANG_AVAILABLE) Log.w(TAG, "TTS 한국어 음성 없음: $language")
        val started = SystemClock.elapsedRealtime()
        // TTS 엔진이 죽으면 onDone·onError가 오지 않는다. 기다리기만 하면 Controller가 바쁜 상태로 멈춘다.
        val finished = withTimeoutOrNull(SPEAK_BASE_MS + SPEAK_PER_CHAR_MS * text.length) {
            suspendCancellableCoroutine { cont ->
                val id = UUID.randomUUID().toString()
                speaking[id] = cont
                cont.invokeOnCancellation { if (speaking.remove(id) != null) tts.stop() }
                if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                    speaking.remove(id)?.resume(Unit)
                }
            }
        }
        if (finished == null) {
            Log.w(TAG, "TTS 응답 없음 ${SystemClock.elapsedRealtime() - started}ms, 건너뜀: $text")
            return
        }
        Log.d(TAG, "TTS ${SystemClock.elapsedRealtime() - started}ms: $text")
    }

    override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): String? = usingMic {
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) { recognize(onReady) }
        }
    }

    /** 유예 듣기가 마이크를 내려놓게 한다. 머고 목소리를 듣지 않고, SpeechRecognizer와 부딪히지도 않는다. */
    private suspend fun <T> usingMic(block: suspend () -> T): T {
        onMicBusy(true)
        try {
            return block()
        } finally {
            onMicBusy(false)
        }
    }

    override suspend fun listenYesNo(timeoutMs: Long): YesNo {
        val said = listen(timeoutMs)
        val answer = YesNoParser.parse(said)
        Log.d(TAG, "음성 답변 '$said' -> $answer")
        return answer
    }

    override fun cancelListening() {
        main.post { recognizer?.let(::release) }
    }

    fun close() {
        cancelListening()
        tts.shutdown()
    }

    private fun release(speech: SpeechRecognizer) {
        if (recognizer !== speech) return
        recognizer = null
        speech.destroy()
    }

    private suspend fun recognize(onReady: () -> Unit): String? = suspendCancellableCoroutine { cont ->
        val speech = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = speech
        cont.invokeOnCancellation { main.post { release(speech) } }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val said = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                release(speech)
                if (cont.isActive) cont.resume(said)
            }

            override fun onError(error: Int) {
                release(speech)
                if (cont.isActive) cont.resume(null)
            }

            override fun onReadyForSpeech(params: Bundle?) { if (cont.isActive) onReady() }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR"),
        )
    }

    private companion object {
        const val TAG = "Mergo"
        const val TTS_INIT_MS = 3_000L

        // 한국어 TTS는 한 글자에 약 0.2초. 넉넉히 잡아 정상 재생은 끊지 않는다.
        const val SPEAK_BASE_MS = 3_000L
        const val SPEAK_PER_CHAR_MS = 300L
    }
}
