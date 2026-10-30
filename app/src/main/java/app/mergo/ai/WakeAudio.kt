package app.mergo.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** 호출어 앞부분이 잘리지 않도록 최근 소리를 들고 있는 버퍼. */
class AudioRing(private val capacity: Int) {
    private val samples = ShortArray(capacity)
    private var next = 0
    private var filled = 0

    fun add(input: ShortArray, count: Int) {
        for (i in 0 until count) {
            samples[next] = input[i]
            next = (next + 1) % capacity
        }
        filled = minOf(capacity, filled + count)
    }

    fun snapshot(): ShortArray = latest(filled)

    /** 가장 최근 [count]개를 순서대로 돌려준다. 들어온 것이 적으면 있는 만큼만 돌려준다. */
    fun latest(count: Int): ShortArray {
        val size = minOf(count, filled)
        val start = (next - size + capacity) % capacity
        return ShortArray(size) { samples[(start + it) % capacity] }
    }
}

object WavEncoder {
    private const val HEADER_BYTES = 44
    private const val BYTES_PER_SAMPLE = 2

    /** 16비트 모노 PCM을 Gemini에 보낼 WAV로 감싼다. */
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * BYTES_PER_SAMPLE
        val out = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(HEADER_BYTES - 8 + dataBytes)
        out.put("WAVEfmt ".toByteArray()).putInt(16)
        out.putShort(1).putShort(1)
        out.putInt(sampleRate).putInt(sampleRate * BYTES_PER_SAMPLE)
        out.putShort(BYTES_PER_SAMPLE.toShort()).putShort(16)
        out.put("data".toByteArray()).putInt(dataBytes)
        pcm.forEach { out.putShort(it) }
        return out.array()
    }
}

/** 기기 안에서 호출어 비슷한 소리를 고르는 1차 관문. 잘못 걸려도 Gemini가 한 번 더 확인한다. */
object WakeGate {
    /**
     * 사전에 "머고야"가 없어서 소리가 같은 토큰으로 듣는다.
     * 일반 대화가 빠질 자리가 없으면 모든 소리가 호출어로 끼워 맞춰져서 흔한 말을 함께 넣는다.
     */
    private val WORDS = listOf(
        "머", "고", "야", "잘못", "눌렀", "아까", "이거", "소리", "그거", "거", "네", "아니", "오늘", "날씨",
        "뭐", "잘", "모르겠", "어", "다", "요", "저기", "좋", "안", "나", "돼", "[unk]",
    )

    fun grammar(): String = WORDS.joinToString(",", "[", "]") { "\"$it\"" }

    /** 놓치는 것보다 한 번 더 확인하는 쪽이 낫다. 받아쓰기로 다시 걸러지므로 느슨하게 고른다. */
    fun suspects(text: String): Boolean = PATTERNS.any { it in text }

    /** 호출어의 첫 소리만 들렸다. 이것만으로는 보내지 않고 [SecondChance]를 연다. */
    fun nearMiss(text: String): Boolean = text.split(" ").any { it in FIRST_SOUNDS }

    private val PATTERNS = listOf("고 야", "머 고", "뭐 고", "머 야", "뭐 야", "머 어", "고 다")

    private val FIRST_SOUNDS = setOf("머", "뭐")
}

/**
 * 시리의 "두 번째 기회". 호출어에 조금 못 미친 소리를 듣거나 받아쓰기에서 탈락하면 몇 초 동안 귀를 더 연다.
 * 못 알아들은 줄 알고 다시 부르면 그때는 바로 듣기 위해서다. 다시 불러도 안 되면 한 번은 열지 않아서,
 * 영상 소리처럼 말이 이어져도 전송이 계속되지 않는다.
 */
class SecondChance(private val windowMs: Long) {
    private var openUntil = 0L
    private var spent = false

    @Synchronized
    fun isOpen(now: Long): Boolean = now < openUntil

    @Synchronized
    fun open(now: Long) {
        if (spent) spent = false else openUntil = now + windowMs
    }

    /** 열려 있는 동안 다시 불렀다. */
    @Synchronized
    fun spend() {
        openUntil = 0
        spent = true
    }

    /** 호출어를 확인했다. 다음 실패는 새로운 시도로 본다. */
    @Synchronized
    fun reset() {
        openUntil = 0
        spent = false
    }
}

/** 소음이 있으면 음성 인식의 말 끝 판정이 오지 않아서, 주변 소음과 비교해 목소리가 잦아든 때를 찾는다. */
class SpeechEndDetector(private val chunkMs: Long) {
    private var floor = 0.0
    private var elapsedMs = 0L
    private var quietMs = 0L

    /** 지금 소음 수준. 기준값을 맞출 때 로그로 본다. */
    val noiseFloor: Double get() = floor

    /** 말이 없을 때의 음량으로 주변 소음 수준을 익힌다. 목소리에 끌려 올라가지 않도록 내려갈 때 더 빨리 따라간다. */
    fun idle(level: Double) {
        floor = when {
            floor == 0.0 -> level
            level < floor -> floor * 0.7 + level * 0.3
            else -> floor * 0.98 + level * 0.02
        }
    }

    fun begin() {
        elapsedMs = 0
        quietMs = 0
    }

    /** 말이 끝났다고 볼 수 있으면 true다. */
    fun update(level: Double): Boolean {
        elapsedMs += chunkMs
        val quiet = level < max(floor * QUIET_RATIO, MIN_SPEECH_LEVEL)
        quietMs = if (quiet) quietMs + chunkMs else 0
        return elapsedMs >= MIN_SPEECH_MS && quietMs >= QUIET_MS
    }

    private companion object {
        const val QUIET_RATIO = 1.5
        const val MIN_SPEECH_LEVEL = 200.0
        const val MIN_SPEECH_MS = 1_000L
        const val QUIET_MS = 500L
    }
}

object AudioLevel {
    fun rms(samples: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / count)
    }
}

/** 호출어를 잘 듣기 위한 방법들. 문제가 생기면 하나씩 꺼서 원인을 가릴 수 있게 나눠 둔다. */
data class WakeOptions(
    val secondChance: Boolean = true,
    val trim: Boolean = true,
    val endDetect: Boolean = true,
    val warmUp: Boolean = true,
) {
    companion object {
        /** 예: "trim,end"는 소리 줄이기와 말 끝 판정을 끈다. */
        fun without(off: String?): WakeOptions {
            val names = off.orEmpty().split(",").map { it.trim() }
            return WakeOptions(
                secondChance = "second" !in names,
                trim = "trim" !in names,
                endDetect = "end" !in names,
                warmUp = "warm" !in names,
            )
        }
    }
}
