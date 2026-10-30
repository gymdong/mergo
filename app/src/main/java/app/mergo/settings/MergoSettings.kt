package app.mergo.settings

import android.content.Context
import app.mergo.model.SettingsSource

class MergoSettings(context: Context) : SettingsSource {
    private val prefs = context.applicationContext.getSharedPreferences("mergo_settings", Context.MODE_PRIVATE)

    override var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD).orEmpty().ifBlank { DEFAULT_WAKE_WORD }
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value.trim()).apply()

    /** 켜면 자동 제안과 도와줘 호출 모두 확인 없이 실행한다. 위험 노드 차단은 이 값과 관계없이 동작한다. */
    override var autoExecute: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EXECUTE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_EXECUTE, value).apply()

    private companion object {
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_AUTO_EXECUTE = "auto_execute"
        const val DEFAULT_WAKE_WORD = "머고야"
    }
}
