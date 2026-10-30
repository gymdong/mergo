package app.mergo.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import app.mergo.BuildConfig
import app.mergo.model.SettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * 호출어를 두 단계로 듣는다. 기기 안의 Vosk가 호출어 비슷한 소리를 고르면,
 * 그 앞뒤 몇 초만 Gemini로 보내 받아쓰고 호출어가 맞을 때만 뒤의 말과 함께 넘긴다.
 * 소리는 의심될 때만 나가고, TTS나 SpeechRecognizer가 쓰는 동안에는 마이크를 내려놓는다.
 * 듣는 중에는 화면을 가리지 않는다. 호출어가 확정되기 전이라 일상 대화에도 뜨기 때문이다.
 */
class WakeListener(
    context: Context,
    private val settings: SettingsSource,
    private val speech: SpeechToText,
    private val log: (String) -> Unit = {},
    private val onWake: (String?) -> Unit,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chance = SecondChance(SECOND_CHANCE_MS)

    @Volatile var options = WakeOptions()

    @Volatile private var running = false

    @Volatile private var micUsers = 0

    @Volatile private var checking = false

    private var lastSentAt = 0L

    /** 디버그용: 마이크 대신 흘려보낼 소리. 녹음 파일로 같은 시험을 반복하기 위해 쓴다. */
    @Volatile private var replay: ShortArray? = null
    private var replayAt = 0

    /** 16kHz 모노 16비트 WAV를 실제 듣기 경로에 그대로 흘려보낸다. */
    fun replay(file: File) {
        if (!file.exists()) {
            log("녹음 파일 없음: ${file.name}")
            return
        }
        val bytes = file.readBytes()
        val pcm = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, bytes.size - WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        replayAt = 0
        replay = ShortArray(pcm.remaining()) { pcm.get(it) }
        log("녹음 재생 시작: ${file.name} ${replay?.size?.div(SAMPLE_RATE)}초")
    }

    fun start() {
        StorageService.unpack(app, ASSET_DIR, TARGET_DIR, { model ->
            running = true
            thread(name = "mergo-wake", isDaemon = true) { listenLoop(model) }
        }, { error -> log("호출어 모델 준비 실패: ${error.message}") })
    }

    /** VoiceIO가 말하거나 듣기 전후에 부른다. */
    @Synchronized
    fun micBusy(busy: Boolean) {
        micUsers = if (busy) micUsers + 1 else maxOf(0, micUsers - 1)
    }

    fun shutdown() {
        running = false
        scope.cancel()
    }

    private fun listenLoop(model: Model) {
        val gate = Recognizer(model, SAMPLE_RATE.toFloat(), WakeGate.grammar())
        val ring = AudioRing(SAMPLE_RATE * RING_SECONDS)
        val samples = ShortArray(CHUNK_SAMPLES)
        val speechEnd = SpeechEndDetector(CHUNK_MS)
        var mic: AudioRecord? = null
        var suspectedAt = 0L
        var retryAt = 0L
        var loudest = 0.0
        var loudestAt = 0L
        log("호출어 듣기 준비 완료")

        while (running) {
            if (micUsers > 0) {
                mic?.run {
                    stop()
                    release()
                }
                mic = null
                suspectedAt = 0L
                retryAt = 0L
                gate.reset()
                SystemClock.sleep(IDLE_MS)
                continue
            }
            val active = mic ?: openMic().also { mic = it }
            if (active == null) {
                SystemClock.sleep(RETRY_MS)
                continue
            }
            val read = active.read(samples, 0, samples.size)
            if (read <= 0) continue
            replay?.let { audio ->
                for (i in 0 until read) samples[i] = audio.getOrElse(replayAt + i) { 0 }
                replayAt += read
                if (replayAt >= audio.size + SAMPLE_RATE * REPLAY_TAIL_SECONDS) {
                    replay = null
                    log("녹음 재생 끝")
                }
            }
            ring.add(samples, read)
            val level = AudioLevel.rms(samples, read)
            if (BuildConfig.DEBUG) {
                loudest = maxOf(loudest, level)
                if (SystemClock.elapsedRealtime() - loudestAt >= LEVEL_LOG_MS) {
                    log("음량 최대 ${loudest.toInt()}, 소음 ${speechEnd.noiseFloor.toInt()}")
                    loudest = 0.0
                    loudestAt = SystemClock.elapsedRealtime()
                }
            }

            val ended = gate.acceptWaveForm(samples, read)
            val text = textOf(if (ended) gate.result else gate.partialResult)
            if (ended) {
                gate.reset()
                if (BuildConfig.DEBUG && text.isNotEmpty()) log("1차 감지 문장: $text")
            }
            val now = SystemClock.elapsedRealtime()

            if (suspectedAt == 0L) {
                speechEnd.idle(level)
                val current = options
                if (checking) {
                    // 답을 기다리다 다시 부른 말이다. 앞의 확인이 실패하면 이 소리부터 다시 본다.
                    if (current.secondChance && retryAt == 0L && WakeGate.nearMiss(text)) retryAt = now
                } else {
                    val open = current.secondChance && chance.isOpen(now)
                    val heardAt = when {
                        open && retryAt != 0L -> retryAt
                        open && WakeGate.nearMiss(text) -> now
                        WakeGate.suspects(text) && now - lastSentAt >= MIN_GAP_MS -> now
                        else -> 0L
                    }
                    retryAt = 0L
                    if (heardAt != 0L) {
                        suspectedAt = heardAt
                        speechEnd.begin()
                        if (open) chance.spend()
                        log("호출어 의심: $text (음량 ${level.toInt()}, 소음 ${speechEnd.noiseFloor.toInt()}, 두 번째 기회 $open)")
                        if (current.warmUp) warmUp()
                    } else if (ended && current.secondChance && WakeGate.nearMiss(text)) {
                        chance.open(now)
                        log("호출어에 못 미침: $text")
                    }
                }
            } else {
                val elapsed = now - suspectedAt
                val current = options
                val voiceEnded = current.endDetect && speechEnd.update(level)
                if (voiceEnded || elapsed >= MAX_CAPTURE_MS || (ended && elapsed >= MIN_CAPTURE_MS)) {
                    val pcm = if (current.trim) {
                        ring.latest(((elapsed + PRE_ROLL_MS) * SAMPLE_RATE / 1_000).toInt())
                    } else {
                        ring.snapshot()
                    }
                    suspectedAt = 0L
                    lastSentAt = now
                    gate.reset()
                    check(pcm, elapsed, current)
                }
            }
        }
        mic?.run {
            stop()
            release()
        }
        gate.close()
        model.close()
    }

    private fun warmUp() {
        scope.launch {
            try {
                speech.warmUp()
            } catch (e: LlmException) {
                log("연결 미리 열기 실패: ${e.message}")
            }
        }
    }

    /** 받아쓴 글자에 호출어가 있을 때만 앱을 깨운다. */
    private fun check(pcm: ShortArray, capturedMs: Long, current: WakeOptions) {
        checking = true
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val seconds = pcm.size.toFloat() / SAMPLE_RATE
            try {
                val wav = WavEncoder.encode(pcm, SAMPLE_RATE)
                if (BuildConfig.DEBUG) File(app.getExternalFilesDir(null), "last-wake.wav").writeBytes(wav)
                val said = withTimeoutOrNull(LlmHttp.TIMEOUT_MS) { speech.transcribe(wav, current.warmUp) }
                val spent = SystemClock.elapsedRealtime() - started
                val timing = "듣기 ${capturedMs}ms + 받아쓰기 ${spent}ms = ${capturedMs + spent}ms, 소리 %.1f초".format(seconds)
                log(if (BuildConfig.DEBUG) "$timing: $said" else timing)
                val hit = said?.let { WakePhrase.find(it, settings.wakeWord) }
                if (hit != null) {
                    chance.reset()
                    log("호출어 확인, 이어진 말: ${hit.command}")
                    onWake(hit.command)
                } else if (current.secondChance) {
                    chance.open(SystemClock.elapsedRealtime())
                }
            } catch (e: LlmException) {
                if (current.secondChance) chance.open(SystemClock.elapsedRealtime())
                log("받아쓰기 실패: ${e.message}")
            } finally {
                checking = false
            }
        }
    }

    private fun textOf(json: String): String =
        TEXT.find(json)?.groupValues?.get(1).orEmpty()

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, CHUNK_SAMPLES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            log("마이크 열기 실패")
            return null
        }
        record.startRecording()
        return record
    }

    private companion object {
        const val ASSET_DIR = "model-ko"
        const val TARGET_DIR = "vosk"
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_600
        const val CHUNK_MS = 100L
        const val RING_SECONDS = 8
        const val PRE_ROLL_MS = 1_500L
        const val MIN_CAPTURE_MS = 1_500L
        const val MAX_CAPTURE_MS = 4_000L
        const val MIN_GAP_MS = 6_000L
        const val SECOND_CHANCE_MS = 5_000L
        const val IDLE_MS = 100L
        const val RETRY_MS = 2_000L
        const val WAV_HEADER_BYTES = 44
        const val REPLAY_TAIL_SECONDS = 3
        const val LEVEL_LOG_MS = 5_000L
        val TEXT = Regex("\"(?:text|partial)\"\\s*:\\s*\"(.*?)\"")
    }
}
