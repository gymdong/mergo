package app.mergo.ai

import app.mergo.model.RecipeIds
import app.mergo.model.VoiceIO
import app.mergo.model.YesNo
import kotlinx.coroutines.delay

/** 실제 Gemini 시험에서 나온 오답을 재현한다: 근거가 없으면 무조건 screen_agent를 고른다. */
class WrongGuessLlm : LlmClient {
    override suspend fun complete(system: String, user: String): String {
        val recipe = when {
            "영상 제목:" in user -> RecipeIds.RESTORE_VIDEO
            "앱:" in user -> RecipeIds.OPEN_PREVIOUS_APP
            "볼륨:" in user -> RecipeIds.SET_VOLUME
            "인터넷:" in user -> RecipeIds.OPEN_INTERNET_PANEL
            else -> RecipeIds.SCREEN_AGENT
        }
        return """{"diagnosis": "가짜", "recipe": "$recipe", "params": {}, "say": "해 드릴까요?", "confirm": false}"""
    }
}

class ScriptedLlm(
    private val delayMs: Long = 0,
    private val failure: LlmException? = null,
    private val replies: List<String> = emptyList(),
) : LlmClient {
    var calls = 0
        private set

    override suspend fun complete(system: String, user: String): String {
        calls++
        delay(delayMs)
        failure?.let { throw it }
        return replies[minOf(calls - 1, replies.lastIndex)]
    }
}

class RecordingVoice(vararg answers: YesNo) : VoiceIO {
    private val queue = answers.toMutableList()
    val spoken = mutableListOf<String>()
    var questions = 0
        private set

    override suspend fun speak(text: String) {
        spoken += text
    }

    override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): String? = null

    override suspend fun listenYesNo(timeoutMs: Long): YesNo {
        questions++
        return queue.removeFirstOrNull() ?: YesNo.NONE
    }
}
