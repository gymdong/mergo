package app.mergo.ai

import android.content.Context
import android.content.Intent
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
class AndroidVoiceIO(context: Context) : VoiceIO {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val ready = CompletableDeferred<Boolean>()
    private val speaking = ConcurrentHashMap<String, Continuation<Unit>>()

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
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

    override suspend fun speak(text: String) {
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

    override suspend fun listen(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) { recognize() }
    }

    override suspend fun listenYesNo(timeoutMs: Long): YesNo = YesNoParser.parse(listen(timeoutMs))

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

    private suspend fun recognize(): String? = suspendCancellableCoroutine { cont ->
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

            override fun onReadyForSpeech(params: Bundle?) = Unit
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
