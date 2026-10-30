package app.mergo.ai

import android.util.Log
import app.mergo.BuildConfig
import app.mergo.agent.LlmScreenAgent
import app.mergo.model.Brain
import app.mergo.model.Clock
import app.mergo.model.ScreenAgent
import app.mergo.model.ScreenReader
import app.mergo.model.SettingsSource
import app.mergo.model.VoiceIO

/** MergoService가 B의 구현을 조립할 때 쓰는 지점. */
object MergoAi {
    private const val TAG = "Mergo"

    private val llm: LlmClient by lazy {
        LlmFactory.create(
            provider = BuildConfig.LLM_PROVIDER,
            geminiKey = BuildConfig.GEMINI_API_KEY,
            geminiModel = BuildConfig.GEMINI_MODEL,
            claudeKey = BuildConfig.CLAUDE_API_KEY,
            claudeModel = BuildConfig.CLAUDE_MODEL,
        )
    }

    /** Gemini 키가 없으면 null이다. 이때 호출어는 꺼지고 도와줘 버튼만 쓴다. */
    fun speechToText(): SpeechToText? = BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
        ?.let { GeminiSpeech(it, BuildConfig.GEMINI_SPEECH_MODEL) }

    fun brain(voice: VoiceIO, settings: SettingsSource): Brain =
        MergoBrain(llm, voice, autoExecute = { settings.autoExecute }, log = ::log)

    fun screenAgent(reader: ScreenReader, voice: VoiceIO): ScreenAgent =
        LlmScreenAgent(reader, llm, voice, Clock.SYSTEM, log = ::log)

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}
