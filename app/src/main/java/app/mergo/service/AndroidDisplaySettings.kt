package app.mergo.service

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.util.Log
import app.mergo.recipe.DisplaySettingsPort
import app.mergo.recipe.DisplaySnapshot

/** 시스템 전체의 밝기·글자 크기 설정. WRITE_SETTINGS를 가족이 미리 허용해야 변경할 수 있다. */
class AndroidDisplaySettings(private val context: Context) : DisplaySettingsPort {
    private val resolver get() = context.contentResolver

    override fun canWrite(): Boolean = Settings.System.canWrite(context)

    override fun read(): DisplaySnapshot {
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1).takeIf { it in 1..255 }
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        val fontScale = Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, Float.NaN)
            .takeIf { it.isFinite() && it in 0.5f..2.0f }
        return DisplaySnapshot(brightness, when (mode) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> true
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> false
            else -> null
        }, fontScale)
    }

    override fun setBrightness(value: Int): Boolean {
        if (!canWrite() || value !in 1..255) return false
        return runCatching {
            val oldMode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            val oldBrightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (oldMode !in listOf(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)) {
                Log.w(TAG, "Cannot read original brightness mode")
                return@runCatching false
            }
            if (oldBrightness !in 1..255) {
                Log.w(TAG, "Cannot read original brightness for rollback")
                return@runCatching false
            }
            // 자동 밝기를 먼저 끄면 예전의 어두운 수동 값이 순간 적용될 수 있다. 목표값을 먼저 쓴다.
            if (!Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)) return@runCatching false
            if (oldMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) return@runCatching true
            try {
                if (Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)) true
                else {
                    restoreBrightness(oldBrightness)
                    false
                }
            } catch (error: Exception) {
                restoreBrightness(oldBrightness)
                throw error
            }
        }.onFailure { Log.w(TAG, "Brightness change failed", it) }.getOrDefault(false)
    }

    override fun setFontScale(value: Float): Boolean {
        if (!canWrite() || !value.isFinite() || value !in 0.5f..2.0f) return false
        return runCatching {
            val config = Configuration()
            Settings.System.getConfiguration(resolver, config)
            config.fontScale = value
            Settings.System.putConfiguration(resolver, config)
        }.onFailure { Log.w(TAG, "Font scale change failed", it) }.getOrDefault(false)
    }

    private fun restoreBrightness(value: Int) {
        runCatching { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value) }
            .onSuccess { if (!it) Log.e(TAG, "Brightness rollback failed") }
            .onFailure { Log.e(TAG, "Brightness rollback failed", it) }
    }

    private companion object { const val TAG = "MergoDisplay" }
}
