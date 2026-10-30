package app.mergo.settings

import android.content.Context
import android.provider.Settings
import app.mergo.model.SettingsSource

class MergoSettings(context: Context) : SettingsSource {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("mergo_settings", Context.MODE_PRIVATE)

    override var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD).orEmpty().ifBlank { DEFAULT_WAKE_WORD }
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value.trim()).apply()

    /** 켜면 자동 제안과 도와줘 호출 모두 확인 없이 실행한다. 위험 노드 차단은 이 값과 관계없이 동작한다. */
    override var autoExecute: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EXECUTE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_EXECUTE, value).apply()

    /** 저장해 둔 편한 값으로 갑작스러운 시각 설정 저하를 되돌리는 별도 선택권. */
    var displayAutoRescue: Boolean
        get() = prefs.getBoolean(KEY_DISPLAY_AUTO_RESCUE, false)
        set(value) = prefs.edit().putBoolean(KEY_DISPLAY_AUTO_RESCUE, value).apply()

    var preferredBrightness: Int?
        get() = prefs.getInt(KEY_PREFERRED_BRIGHTNESS, -1).takeIf { it in 1..255 }
        set(value) {
            prefs.edit().apply {
                if (value != null && value in 1..255) putInt(KEY_PREFERRED_BRIGHTNESS, value)
                else remove(KEY_PREFERRED_BRIGHTNESS)
            }.apply()
        }

    var preferredFontScale: Float?
        get() = prefs.getFloat(KEY_PREFERRED_FONT_SCALE, Float.NaN).takeIf { it.isFinite() && it in 0.5f..2f }
        set(value) {
            prefs.edit().apply {
                if (value != null && value.isFinite() && value in 0.5f..2f) putFloat(KEY_PREFERRED_FONT_SCALE, value)
                else remove(KEY_PREFERRED_FONT_SCALE)
            }.apply()
        }

    /** 가족이 밝기·글자를 편하게 맞춘 상태를 기준으로 함께 저장한다. 일부만 저장하지 않는다. */
    fun saveCurrentDisplayPreferences(): Boolean {
        val brightness = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        val fontScale = Settings.System.getFloat(app.contentResolver, Settings.System.FONT_SCALE, Float.NaN)
        if (brightness !in 1..255 || !fontScale.isFinite() || fontScale !in 0.5f..2f) return false
        prefs.edit()
            .putInt(KEY_PREFERRED_BRIGHTNESS, brightness)
            .putFloat(KEY_PREFERRED_FONT_SCALE, fontScale)
            .apply()
        return true
    }

    private companion object {
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_AUTO_EXECUTE = "auto_execute"
        const val KEY_DISPLAY_AUTO_RESCUE = "display_auto_rescue"
        const val KEY_PREFERRED_BRIGHTNESS = "preferred_brightness"
        const val KEY_PREFERRED_FONT_SCALE = "preferred_font_scale"
        const val DEFAULT_WAKE_WORD = "머고야"
    }
}
