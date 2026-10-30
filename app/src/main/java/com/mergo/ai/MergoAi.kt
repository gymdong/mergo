package com.mergo.ai

import android.util.Log
import com.mergo.BuildConfig
import com.mergo.agent.LlmScreenAgent
import com.mergo.model.Brain
import com.mergo.model.Clock
import com.mergo.model.ScreenAgent
import com.mergo.model.ScreenReader
import com.mergo.model.VoiceIO
import com.mergo.settings.MergoSettings

/** A의 MergoController가 B의 구현을 주입받을 때 쓰는 조립 지점. */
object MergoAi {
    private const val TAG = "Mergo"

    private val llm: LlmClient by lazy {
        LlmFactory.create(
            provider = BuildConfig.LLM_PROVIDER,
            geminiKey = BuildConfig.GEMINI_API_KEY,
            geminiModel = BuildConfig.GEMINI_MODEL,
            claudeKey = BuildConfig.ANTHROPIC_API_KEY,
            claudeModel = BuildConfig.CLAUDE_MODEL,
        )
    }

    fun brain(voice: VoiceIO, settings: MergoSettings): Brain =
        MergoBrain(llm, voice, autoExecute = { settings.autoExecute }, log = ::log)

    fun screenAgent(reader: ScreenReader, voice: VoiceIO): ScreenAgent =
        LlmScreenAgent(reader, llm, voice, Clock { System.currentTimeMillis() }, log = ::log)

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}
