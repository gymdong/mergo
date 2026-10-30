package app.mergo.core

import app.mergo.core.fakes.DefaultSettings
import app.mergo.detect.Cooldown
import app.mergo.detect.DetectorConfig
import app.mergo.detect.RuleId
import app.mergo.detect.StuckDetector
import app.mergo.detect.Suggestion
import app.mergo.memory.BaselineStore
import app.mergo.memory.FamilyLog
import app.mergo.memory.TimelineBuffer
import app.mergo.model.Brain
import app.mergo.model.BrainContext
import app.mergo.model.BrainDecision
import app.mergo.model.Clock
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.YesNo
import app.mergo.recipe.FakeVoice
import app.mergo.ui.OverlayPort
import app.mergo.ui.Prompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergoControllerTest {

    private class FakeOverlay(
        var answer: YesNo? = null,
        var choice: String? = null,
        var answerAfterMs: Long = 1_000,
        /** 제목별로 다른 답을 주고 싶을 때 */
        val answers: Map<String, YesNo> = emptyMap(),
    ) : OverlayPort {
        val asked = mutableListOf<String>()
        val progress = mutableListOf<String?>()
        val done = mutableListOf<String>()
        override suspend fun ask(prompt: Prompt, timeoutMs: Long): YesNo {
            asked += prompt.title
            answers[prompt.title]?.let { delay(answerAfterMs); return it }
            val a = answer ?: run { delay(timeoutMs); return YesNo.NONE }
            delay(answerAfterMs)
            return a
        }
        override suspend fun choose(prompt: String, choices: List<String>, timeoutMs: Long): String? {
            val c = choice ?: run { delay(timeoutMs); return null }
            delay(answerAfterMs)
            return c
        }
        override fun progress(text: String?) { progress += text }
        override fun done(title: String, body: String) { done += title }
        override fun dismiss() {}
    }

    private class FakeRecipe(override val id: String, val ok: Boolean) : Recipe {
        val calls = mutableListOf<Map<String, String>>()
        override suspend fun run(params: Map<String, String>): Boolean { calls += params; return ok }
    }

    private class Env(
        scope: TestScope,
        recipeOk: Boolean = true,
        val overlay: FakeOverlay = FakeOverlay(),
        val voice: FakeVoice = FakeVoice(),
        autoExecute: Boolean = false,
        decision: BrainDecision? = null,
    ) {
        val clock = Clock { scope.testScheduler.currentTime }
        val cooldown = Cooldown()
        val baseline = BaselineStore(clock, emptySet())
        val detector = StuckDetector(clock, DetectorConfig(), cooldown) { baseline.current?.mediaTitle }
        val recipe = FakeRecipe(RecipeIds.RESTORE_VIDEO, recipeOk)
        val familyLog = FamilyLog(null)
        val brainCalls = mutableListOf<BrainContext>()
        val controller = MergoController(
            scope, clock, TimelineBuffer(clock), baseline, detector, cooldown,
            mapOf(recipe.id to recipe),
            object : Brain {
                override suspend fun decide(ctx: BrainContext): BrainDecision {
                    brainCalls += ctx
                    return decision ?: BrainDecision("", RecipeIds.RESTORE_VIDEO, emptyMap(), "아까 보시던 영상으로 돌아갈까요?", true)
                }
            },
            voice, overlay, DefaultSettings(autoExecute = autoExecute), familyLog,
            liveVolume = { 8 }, liveOnline = { true },
        )
        val suggestion = Suggestion.restoreVideo(RuleId.R1_AUTOPLAY, "트로트 메들리", "autoplay", 0)
    }

    @Test
    fun `말풍선에서 예를 누르면 복구 중 안내 뒤 레시피를 실행하고 완료를 알린다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES))
        env.controller.onSuggestion(env.suggestion)
        assertEquals(listOf(mapOf("title" to "트로트 메들리", "mode" to "autoplay")), env.recipe.calls)
        assertEquals(listOf("영상이 바뀐 것 같아요", "이 화면이 맞나요?"), env.overlay.asked)
        assertEquals(listOf("아까 보던 영상으로 돌아가고 있어요", null), env.overlay.progress)
        assertEquals(listOf("원래 영상으로 돌아왔어요"), env.overlay.done)
        val log = env.familyLog.all().single()
        assertTrue("자동 해결로 기록한다", log.resolved && !log.notified)
        assertEquals("이전 영상을 다시 찾았어요", log.summary)
    }

    @Test
    fun `복구 뒤 이 화면이 아니라고 하면 가족 알림을 묻는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, answers = mapOf("이 화면이 맞나요?" to YesNo.NO)))
        env.controller.onSuggestion(env.suggestion)
        assertEquals(listOf("영상이 바뀐 것 같아요", "이 화면이 맞나요?", "제가 해결하지 못했어요"), env.overlay.asked)
        val log = env.familyLog.all().single()
        assertTrue(!log.resolved && log.notified)
    }

    @Test
    fun `복구 뒤 확인 질문에 답이 없으면 맞는 것으로 본다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, answers = mapOf("이 화면이 맞나요?" to YesNo.NONE)))
        env.controller.onSuggestion(env.suggestion)
        assertTrue(env.familyLog.all().single().resolved)
    }

    @Test
    fun `음성 응이 버튼보다 먼저 오면 음성으로 동의한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.NO, answerAfterMs = 10_000), voice = FakeVoice(yesNo = YesNo.YES))
        env.controller.onSuggestion(env.suggestion)
        assertEquals(1, env.recipe.calls.size)
    }

    @Test
    fun `음성을 못 알아들으면 버튼 답을 기다린다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, answerAfterMs = 8_000), voice = FakeVoice(yesNo = YesNo.NONE))
        env.controller.onSuggestion(env.suggestion)
        assertEquals(1, env.recipe.calls.size)
    }

    // AC-D6
    @Test
    fun `아니요를 누르면 쿨다운에 넣고 지금 상태를 정상 상태로 인정한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.NO))
        env.controller.onSuggestion(env.suggestion)
        assertTrue(env.recipe.calls.isEmpty())
        assertTrue(env.cooldown.isBlocked(RuleId.R1_AUTOPLAY, "트로트 메들리", testScheduler.currentTime))
        assertEquals("알겠어요", env.voice.spoken.last())
    }

    @Test
    fun `15초 무응답은 거절로 보고 조용히 닫는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = null))
        env.controller.onSuggestion(env.suggestion)
        assertTrue(env.recipe.calls.isEmpty())
        assertTrue(env.cooldown.isBlocked(RuleId.R1_AUTOPLAY, "트로트 메들리", testScheduler.currentTime))
        assertEquals(MergoController.BUBBLE_TIMEOUT_MS, testScheduler.currentTime)
        assertTrue("무응답이면 말하지 않는다", "알겠어요" !in env.voice.spoken)
    }

    @Test
    fun `확인 없이 실행 모드에서는 묻지 않고 바로 실행한다`() = runTest {
        val env = Env(this, autoExecute = true)
        env.controller.onSuggestion(env.suggestion)
        assertTrue(env.overlay.asked.isEmpty())
        assertEquals(1, env.recipe.calls.size)
    }

    // AC-R1
    @Test
    fun `복구에 실패하고 가족 알림에 동의하면 가족 기록에 1건 남긴다`() = runTest {
        val env = Env(this, recipeOk = false, overlay = FakeOverlay(answer = YesNo.YES))
        env.controller.onSuggestion(env.suggestion)
        assertEquals(listOf("영상이 바뀐 것 같아요", "제가 해결하지 못했어요"), env.overlay.asked)
        assertEquals(1, env.familyLog.all().size)
        assertEquals(RecipeIds.RESTORE_VIDEO, env.familyLog.all().single().recipe)
        assertTrue(env.familyLog.all().single().notified)
        assertEquals("가족에게 알렸어요", env.voice.spoken.last())
    }

    @Test
    fun `도와줘 선택지를 누르면 Brain이 고른 레시피를 확인 뒤 실행한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, choice = "아까 보던 거"))
        env.controller.onHelp()
        assertEquals("아까 보던 거", env.brainCalls.single().utterance)
        assertEquals(1, env.recipe.calls.size)
        assertEquals("빠진 제목은 정상 상태로 채운다(없으면 빈 값)", "", env.recipe.calls.single()["title"])
    }

    @Test
    fun `Brain이 되묻기로 답하면 실행하지 않고 말만 한다`() = runTest {
        val env = Env(
            this, overlay = FakeOverlay(choice = "오늘 날씨 어때"),
            decision = BrainDecision("범위 밖", RecipeIds.ASK, emptyMap(), "제가 도울 수 있는 일이 아니에요", false),
        )
        env.controller.onHelp()
        assertTrue(env.recipe.calls.isEmpty())
        assertEquals("제가 도울 수 있는 일이 아니에요", env.voice.spoken.last())
    }

    @Test
    fun `처리 중에는 다른 제안을 받지 않는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, answerAfterMs = 5_000))
        val first = launch { env.controller.onSuggestion(env.suggestion) }
        delay(100)
        env.controller.onSuggestion(env.suggestion.copy(target = "다른 영상"))
        first.join()
        assertEquals(1, env.recipe.calls.size)
    }
}
