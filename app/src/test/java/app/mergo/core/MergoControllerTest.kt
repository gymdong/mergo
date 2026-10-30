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
import app.mergo.model.EventKind
import app.mergo.model.GraceListener
import app.mergo.model.Marker
import app.mergo.model.ScreenEvent
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

    class FakeGrace : GraceListener {
        var openFor: Long? = null
        var onHeard: ((String) -> Unit)? = null
        var closes = 0
        override fun open(durationMs: Long, onHeard: (String) -> Unit) { openFor = durationMs; this.onHeard = onHeard }
        override fun close() { openFor = null; closes++ }
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
        val graceListener = FakeGrace()
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
            grace = graceListener,
        )
        val suggestion = Suggestion.restoreVideo(RuleId.R1_AUTOPLAY, "트로트 메들리", "autoplay", 0)
    }

    @Test
    fun `YouTube 광고를 본 뒤 다른 앱으로 가면 광고 중으로 보지 않는다`() = runTest {
        val env = Env(this)
        val yt = app.mergo.service.YouTubeSignals.PKG
        env.controller.onScreenEvent(ScreenEvent(0, yt, "트로트 메들리", emptyList(), false, EventKind.CONTENT_CHANGED, setOf(Marker.AD_SHOWING)))
        assertTrue(env.controller.brainContext("광고 좀 닫아줘").screenHasAd)

        env.controller.onScreenEvent(ScreenEvent(1_000, "com.android.chrome", null, emptyList(), true, EventKind.WINDOW_CHANGED))
        assertEquals(false, env.controller.brainContext("광고 좀 닫아줘").screenHasAd)
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

    private fun Env.play(ts: Long, title: String, user: Boolean = false) =
        controller.onScreenEvent(ScreenEvent(ts, "com.google.android.youtube", title, emptyList(), user, EventKind.CONTENT_CHANGED, setOf(Marker.VIDEO_PLAYER)))

    /** 영상 가를 35초 본 뒤(정상 상태), 카운트다운 없이 영상 나로 넘어간 상황. */
    private suspend fun TestScope.mistouched(env: Env) {
        env.baseline.onDevice(8, true, 0)
        env.play(1_000, "영상 가")
        for (t in 2_000L..40_000L step 1_000L) { delay(1_000); env.controller.tick() }
        env.play(testScheduler.currentTime, "영상 나")
        env.controller.tick()
    }

    @Test
    fun `영상이 넘어가면 30초 유예 듣기를 열고, 잘못 눌렀다는 말에 확인 없이 되돌린다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answers = mapOf("이 화면이 맞나요?" to YesNo.YES)))
        mistouched(env)
        assertEquals(30_000L, env.graceListener.openFor)
        env.controller.onGraceHeard("머고야 잘못 눌렀다")
        assertEquals(listOf(mapOf("title" to "영상 가", "mode" to "mistouch")), env.recipe.calls)
        assertTrue("되돌릴지 묻지 않는다", "영상이 바뀐 것 같아요" !in env.overlay.asked)
    }

    @Test
    fun `유예 중이라도 정해진 문장이 아니면 움직이지 않는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES))
        mistouched(env)
        env.controller.onGraceHeard("오늘 날씨가 참 좋네요")
        assertTrue(env.recipe.calls.isEmpty())
    }

    @Test
    fun `30초가 지나면 유예 듣기를 닫고 그 뒤의 말은 무시한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES))
        mistouched(env)
        repeat(31) { delay(1_000); env.controller.tick() }
        assertEquals(null, env.graceListener.openFor)
        env.controller.onGraceHeard("잘못 눌렀다")
        assertTrue(env.recipe.calls.isEmpty())
    }
}
