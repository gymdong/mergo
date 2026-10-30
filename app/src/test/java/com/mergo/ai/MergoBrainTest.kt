package com.mergo.ai

import com.mergo.model.Baseline
import com.mergo.model.BrainContext
import com.mergo.model.YesNo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MergoBrainTest {
    private val goodReply =
        """{"diagnosis": "자동재생", "recipe": "restore_video", "params": {"title": "○○"}, "say": "돌아갈까요?", "confirm": false}"""

    private fun ctx(said: String?, diff: List<String> = emptyList()) = BrainContext(
        said, diff, emptyList(), Baseline("com.android.launcher", null, 8, true, 0), false,
    )

    @Test
    fun `후보가 하나면 LLM을 부르지 않는다`() = runTest {
        val llm = ScriptedLlm(replies = listOf(goodReply))
        val decision = MergoBrain(llm, RecordingVoice()).decide(ctx("소리가 안 나"))
        assertEquals(RecipeId.SET_VOLUME, decision.recipe)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `자동재생 복원에는 이전 영상 제목을 넘긴다`() = runTest {
        val decision = MergoBrain(ScriptedLlm(), RecordingVoice())
            .decide(ctx("아까 보던 거", listOf("영상 제목: ○○ 트로트 → △△ 뉴스 (터치 없음)")))
        assertEquals("○○ 트로트", decision.params["title"])
    }

    @Test
    fun `확인 여부는 모델 값이 아니라 설정을 따른다`() = runTest {
        val modelSaysNo = MergoBrain(ScriptedLlm(replies = listOf(goodReply)), RecordingVoice())
            .decide(ctx("이거 좀 봐 줘 다시", listOf("기타: 변화")))
        assertTrue(modelSaysNo.confirm)

        val auto = MergoBrain(ScriptedLlm(), RecordingVoice(), autoExecute = { true }).decide(ctx("소리가 안 나"))
        assertFalse(auto.confirm)
        assertEquals("소리를 다시 켜 드릴게요.", auto.say)
    }

    @Test
    fun `LLM이 1초 5 넘게 걸리면 잠시만요라고 말하고 빠르면 말하지 않는다`() = runTest {
        val slow = RecordingVoice()
        MergoBrain(ScriptedLlm(delayMs = 2_000, replies = listOf(goodReply)), slow)
            .decide(ctx("이거 다시", listOf("기타: 변화")))
        assertEquals(listOf("잠시만요"), slow.spoken)

        val fast = RecordingVoice()
        MergoBrain(ScriptedLlm(delayMs = 1_000, replies = listOf(goodReply)), fast)
            .decide(ctx("이거 다시", listOf("기타: 변화")))
        assertTrue(fast.spoken.isEmpty())
    }

    @Test
    fun `LLM이 8초를 넘기면 포기하고 ask로 대체한다`() = runTest {
        val decision = MergoBrain(ScriptedLlm(delayMs = 20_000, replies = listOf(goodReply)), RecordingVoice())
            .decide(ctx("이거 다시", listOf("기타: 변화")))
        assertEquals(RecipeId.ASK, decision.recipe)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `키가 없어 LLM이 꺼져 있어도 키워드 경로는 동작한다`() = runTest {
        val brain = MergoBrain(DisabledLlmClient(), RecordingVoice())
        assertEquals(RecipeId.SET_VOLUME, brain.decide(ctx("소리가 안 나")).recipe)
        assertEquals(RecipeId.ASK, brain.decide(ctx("이거 다시", listOf("기타: 변화"))).recipe)
    }

    @Test
    fun `후보가 여럿이고 LLM이 ask면 예아니오로 최대 2번 묻는다`() = runTest {
        val ask = """{"diagnosis": "모호", "recipe": "ask", "params": {}, "say": "무엇을 할까요?", "confirm": false}"""

        val second = RecordingVoice(YesNo.NO, YesNo.YES)
        val chosen = MergoBrain(ScriptedLlm(replies = listOf(ask)), second).decide(ctx("아까 보던 거"))
        assertEquals(RecipeId.OPEN_PREVIOUS_APP, chosen.recipe)
        assertFalse(chosen.confirm)
        assertEquals(2, second.questions)

        val none = RecordingVoice(YesNo.NO, YesNo.NO)
        val gaveUp = MergoBrain(ScriptedLlm(replies = listOf(ask)), none).decide(ctx("아까 보던 거"))
        assertEquals(RecipeId.ASK, gaveUp.recipe)
        assertEquals(2, none.questions)
    }

    @Test
    fun `모호한 말은 화면 변화가 하나면 LLM 없이 그 변화를 되돌린다`() = runTest {
        val llm = ScriptedLlm()
        val brain = MergoBrain(llm, RecordingVoice())
        val video = ctx("이거 이상하다", listOf("영상 제목: ○○ → △△ (터치 없음)"))
        val volume = ctx("뭐가 잘못됐나", listOf("볼륨: 8 → 0"))
        assertEquals(RecipeId.RESTORE_VIDEO, brain.decide(video).recipe)
        assertEquals(RecipeId.SET_VOLUME, brain.decide(volume).recipe)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `근거 없는 발화에는 LLM이 지어낸 약속을 말하지 않는다`() = runTest {
        val promise = """{"diagnosis": "노래", "recipe": "ask", "params": {}, "say": "어떤 노래를 들려드릴까요?", "confirm": true}"""
        val decision = MergoBrain(ScriptedLlm(replies = listOf(promise)), RecordingVoice()).decide(ctx("노래 한 곡 불러 줘"))
        assertEquals(RecipeId.ASK, decision.recipe)
        assertEquals("그건 제가 해 드리기 어려워요. 화면에 문제가 있으신가요?", decision.say)
    }

    @Test
    fun `금지 발화는 LLM에 묻지 않고 바로 ask로 답한다`() = runTest {
        val llm = ScriptedLlm(replies = listOf(goodReply))
        val decision = MergoBrain(llm, RecordingVoice()).decide(ctx("아까 그 돈 보내 줘"))
        assertEquals(RecipeId.ASK, decision.recipe)
        assertEquals(0, llm.calls)
    }
}
