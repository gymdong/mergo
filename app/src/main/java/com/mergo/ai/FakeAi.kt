package com.mergo.ai

import com.mergo.model.Brain
import com.mergo.model.BrainContext
import com.mergo.model.BrainDecision
import com.mergo.model.ScreenAgent
import com.mergo.model.VoiceIO
import com.mergo.model.YesNo

/** A가 B의 구현 없이 Controller를 돌려 볼 때 쓴다. */
class FakeBrain(
    private val decision: BrainDecision = BrainDecision(
        diagnosis = "가짜 판단",
        recipe = RecipeId.RESTORE_VIDEO,
        params = emptyMap(),
        say = "아까 보시던 영상으로 돌아갈까요?",
        confirm = true,
    ),
) : Brain {
    override suspend fun decide(ctx: BrainContext): BrainDecision = decision
}

class FakeVoiceIO(
    private val heard: String? = "아까 보던 거",
    private val answers: MutableList<YesNo> = mutableListOf(),
) : VoiceIO {
    val spoken = mutableListOf<String>()

    override suspend fun speak(text: String) {
        spoken += text
    }

    override suspend fun listen(timeoutMs: Long): String? = heard

    override suspend fun listenYesNo(timeoutMs: Long): YesNo = answers.removeFirstOrNull() ?: YesNo.YES
}

class FakeScreenAgent(private val result: Boolean = true) : ScreenAgent {
    val goals = mutableListOf<String>()

    override suspend fun achieve(goal: String): Boolean {
        goals += goal
        return result
    }
}
