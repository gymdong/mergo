package com.mergo.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mergo.model.VoiceIO
import com.mergo.model.YesNo
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

/** 말하는 내용은 onCaption으로도 넘겨 음성과 화면에 동시에 낸다. */
class AndroidVoiceIO(
    context: Context,
    private val onCaption: (String) -> Unit = {},
) : VoiceIO {
    private val app = context.applicationContext
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
        })
    }

    override suspend fun speak(text: String) {
        onCaption(text)
        val usable = withTimeoutOrNull(TTS_INIT_MS) { ready.await() } ?: false
        if (!usable) return
        tts.language = Locale.KOREAN
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            speaking[id] = cont
            cont.invokeOnCancellation { speaking.remove(id) }
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                speaking.remove(id)?.resume(Unit)
            }
        }
    }

    override suspend fun listen(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) { recognize() }
    }

    override suspend fun listenYesNo(timeoutMs: Long): YesNo = YesNoParser.parse(listen(timeoutMs))

    fun close() {
        tts.shutdown()
    }

    private suspend fun recognize(): String? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
        cont.invokeOnCancellation { recognizer.destroy() }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val said = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                recognizer.destroy()
                if (cont.isActive) cont.resume(said)
            }

            override fun onError(error: Int) {
                recognizer.destroy()
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
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR"),
        )
    }

    private companion object {
        const val TTS_INIT_MS = 3_000L
    }
}
