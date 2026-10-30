package app.mergo.recipe

import app.mergo.model.FailReason
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.RecipeOutcome
import kotlin.math.max

/** 시스템 설정의 관측값. null은 기기에서 읽지 못한 상태다. */
data class DisplaySnapshot(val brightness: Int?, val automaticBrightness: Boolean?, val fontScale: Float?)

/** Android 설정 접근을 분리해 레시피와 감지 정책을 JVM에서 검증한다. */
interface DisplaySettingsPort {
    fun canWrite(): Boolean
    fun read(): DisplaySnapshot
    /** 사용자 요청 시 자동 밝기를 끄고 수동 밝기로 바꾼다. */
    fun setBrightness(value: Int): Boolean
    fun setFontScale(value: Float): Boolean
}

class BrightenScreen(
    private val display: DisplaySettingsPort,
    private val preferred: () -> Int?,
) : Recipe {
    override val id = RecipeIds.BRIGHTEN_SCREEN

    override suspend fun run(params: Map<String, String>): RecipeOutcome {
        if (!display.canWrite()) return RecipeOutcome.Guided("화면 설정을 바꾸려면 가족이 머고의 설정 변경 권한을 켜야 해요.")
        val before = display.read()
        val current = before.brightness ?: return RecipeOutcome.Unverifiable("현재 밝기를 읽지 못함")
        val target = if (params["restore"] == "true") {
            preferred()?.takeIf { it in 1..255 && it > current } ?: return RecipeOutcome.Failed(FailReason.PRECONDITION)
        } else {
            max(current + STEP, preferred()?.takeIf { it in 1..255 } ?: MIN_REQUEST).coerceAtMost(MAX_REQUEST)
        }
        // 자동 밝기에서는 저장값이 높아도 조도 센서가 실제 화면을 어둡게 만들 수 있다.
        // 명시적 요청이라면 값이 255로 같더라도 수동 모드 전환을 시도한다.
        if (target <= current && before.automaticBrightness != true) return RecipeOutcome.Guided("밝기가 이미 충분히 높아요.")
        if (!display.setBrightness(target)) return RecipeOutcome.Failed(FailReason.ERROR)
        return if (Verifier.waitUntil(2_000) {
                val now = display.read()
                now.brightness == target && now.automaticBrightness == false
            }) RecipeOutcome.Verified()
        else RecipeOutcome.Unverifiable("밝기 설정 변경을 확인하지 못함")
    }

    private companion object {
        const val STEP = 48
        const val MIN_REQUEST = 128
        const val MAX_REQUEST = 255
    }
}

class IncreaseTextSize(
    private val display: DisplaySettingsPort,
    private val preferred: () -> Float?,
) : Recipe {
    override val id = RecipeIds.INCREASE_TEXT_SIZE

    override suspend fun run(params: Map<String, String>): RecipeOutcome {
        if (!display.canWrite()) return RecipeOutcome.Guided("글자 크기를 바꾸려면 가족이 머고의 설정 변경 권한을 켜야 해요.")
        val current = display.read().fontScale ?: return RecipeOutcome.Unverifiable("현재 글자 크기 설정을 읽지 못함")
        val target = if (params["restore"] == "true") {
            preferred()?.takeIf { it.isFinite() && it > current && it <= MAX_SCALE }
                ?: return RecipeOutcome.Failed(FailReason.PRECONDITION)
        } else {
            max(current + STEP, preferred()?.takeIf { it.isFinite() && it in 0.5f..MAX_SCALE } ?: MIN_REQUEST)
                .coerceAtMost(MAX_SCALE)
        }
        if (target <= current + EPSILON) return RecipeOutcome.Guided("글자 크기 설정이 이미 가장 커요.")
        if (!display.setFontScale(target)) return RecipeOutcome.Failed(FailReason.ERROR)
        // 설정값의 반영만 확인한다. 다른 앱이 자체 글꼴 크기를 고정했다면 그 글자까지 커졌다는 뜻은 아니다.
        return if (Verifier.waitUntil(2_000) { display.read().fontScale?.let { it >= target - EPSILON } == true })
            RecipeOutcome.Verified()
        else RecipeOutcome.Unverifiable("글자 크기 설정 변경을 확인하지 못함")
    }

    private companion object {
        const val STEP = 0.2f
        const val MIN_REQUEST = 1.2f
        const val MAX_SCALE = 2.0f
        const val EPSILON = 0.01f
    }
}

/** 가족이 저장한 편한 값에서 갑자기 내려갔을 때만 자동 복구한다. 시작 시 낮은 값은 추정하지 않는다. */
class DisplayRescueMonitor {
    private var started = false
    private var brightnessArmed = false
    private var fontArmed = false
    private var brightnessDropStartedAt: Long? = null
    private var fontDropStartedAt: Long? = null

    fun onSnapshot(
        now: DisplaySnapshot, enabled: Boolean, preferredBrightness: Int?, preferredFontScale: Float?,
        atMs: Long = System.currentTimeMillis(),
    ): Set<String> {
        val first = !started
        started = true
        if (!enabled) {
            brightnessArmed = false
            fontArmed = false
            brightnessDropStartedAt = null
            fontDropStartedAt = null
            return emptySet()
        }
        val out = mutableSetOf<String>()
        val brightness = now.brightness
        if (preferredBrightness != null && preferredBrightness in 1..255 &&
            now.automaticBrightness == false && brightness != null
        ) {
            if (brightness >= preferredBrightness * 0.8) {
                brightnessArmed = true
                brightnessDropStartedAt = null
            } else if (brightnessArmed) {
                val start = brightnessDropStartedAt ?: atMs.also { brightnessDropStartedAt = it }
                if (atMs - start > DROP_WINDOW_MS) brightnessArmed = false
                else if (!first && brightness < preferredBrightness * 0.5) {
                    out += RecipeIds.BRIGHTEN_SCREEN
                    brightnessArmed = false
                }
            }
        } else {
            brightnessArmed = false
            brightnessDropStartedAt = null
        }
        val scale = now.fontScale
        if (preferredFontScale != null && preferredFontScale.isFinite() && preferredFontScale in 0.5f..2f && scale != null) {
            if (scale >= preferredFontScale - 0.05f) {
                fontArmed = true
                fontDropStartedAt = null
            } else if (fontArmed) {
                val start = fontDropStartedAt ?: atMs.also { fontDropStartedAt = it }
                if (atMs - start > DROP_WINDOW_MS) fontArmed = false
                else if (!first && scale <= preferredFontScale - 0.2f) {
                    out += RecipeIds.INCREASE_TEXT_SIZE
                    fontArmed = false
                }
            }
        } else {
            fontArmed = false
            fontDropStartedAt = null
        }
        return out
    }

    private companion object { const val DROP_WINDOW_MS = 5_000L }
}
