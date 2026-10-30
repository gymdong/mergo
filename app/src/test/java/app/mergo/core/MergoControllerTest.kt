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

    /**
     * 영상 [current]가 끝나 카운트다운이 뜨고 [next]로 자동재생된 뒤, 12초 동안 아무도 만지지 않는다.
     * YouTube가 새 영상에 맞춰 목록을 스스로 스크롤하는 이벤트도 넣는다(사용자 동작이 아니다).
     */
    private suspend fun TestScope.autoplay(env: Env, current: String, next: String) {
        env.controller.onScreenEvent(
            ScreenEvent(testScheduler.currentTime, "com.google.android.youtube", current, emptyList(), false,
                EventKind.CONTENT_CHANGED, setOf(Marker.VIDEO_PLAYER, Marker.AUTOPLAY_COUNTDOWN)),
        )
        delay(1_000)
        env.play(testScheduler.currentTime, next)
        delay(500)
        env.controller.onScreenEvent(ScreenEvent(testScheduler.currentTime, "com.google.android.youtube", next, emptyList(), true, EventKind.SCROLL))
        repeat(12) { delay(1_000); env.controller.tick() }
    }

    /** 영상 가를 35초 본 뒤 자동재생으로 영상 나가 나오고, 제안에 끝내 답이 없었던 상황. 그 뒤 규칙 휴식 5분을 넘긴다. */
    private suspend fun TestScope.fellAsleep(env: Env) {
        bubbleShowing(env)
        delay(MergoController.BUBBLE_TIMEOUT_MS + 6 * 60_000L)
    }

    private val Env.autoplaySuggestions get() = overlay.asked.count { it == "영상이 바뀐 것 같아요" }

    @Test
    fun `자동 제안에 답이 없으면 잠든 채 자동재생이 이어져도 다시 제안하지 않는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = null))
        fellAsleep(env)
        assertEquals(1, env.autoplaySuggestions)
        autoplay(env, "영상 나", "영상 다")
        assertEquals("무응답 뒤에는 사용자가 움직이기 전까지 조용히 있는다", 1, env.autoplaySuggestions)
    }

    /** 영상 가에서 자동재생으로 영상 나가 나와 말풍선이 뜬 직후. 말풍선은 아직 답을 기다린다. */
    private suspend fun TestScope.bubbleShowing(env: Env) {
        env.baseline.onDevice(8, true, 0)
        env.play(1_000, "영상 가")
        repeat(40) { delay(1_000); env.controller.tick() }
        autoplay(env, "영상 가", "영상 나")
        assertEquals(1, env.autoplaySuggestions)
    }

    @Test
    fun `말풍선이 떠 있는 동안 화면을 만졌으면 답이 없어도 자동 제안을 멈추지 않는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = null))
        bubbleShowing(env)
        env.controller.onScreenEvent(ScreenEvent(testScheduler.currentTime, "com.google.android.youtube", "영상 나", emptyList(), true, EventKind.TOUCH))
        delay(MergoController.BUBBLE_TIMEOUT_MS + 6 * 60_000L)
        autoplay(env, "영상 나", "영상 다")
        assertEquals(2, env.autoplaySuggestions)
    }

    @Test
    fun `말풍선이 떠 있는 동안 도와줘를 눌렀으면 답이 없어도 자동 제안을 멈추지 않는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = null, choice = "아까 보던 거"))
        bubbleShowing(env)
        env.controller.onHelp()
        delay(MergoController.BUBBLE_TIMEOUT_MS + 6 * 60_000L)
        autoplay(env, "영상 나", "영상 다")
        assertEquals(2, env.autoplaySuggestions)
    }

    @Test
    fun `무응답 뒤 사용자가 화면을 만지면 자동 제안을 다시 한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = null))
        fellAsleep(env)
        env.controller.onScreenEvent(ScreenEvent(testScheduler.currentTime, "com.google.android.youtube", "영상 나", emptyList(), true, EventKind.TOUCH))
        delay(40_000)
        autoplay(env, "영상 나", "영상 다")
        assertEquals(2, env.autoplaySuggestions)
    }

    @Test
    fun `무응답 뒤 도와줘를 부르면 자동 제안을 다시 한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = null, choice = "아까 보던 거"))
        fellAsleep(env)
        env.controller.onHelp()
        delay(40_000)
        autoplay(env, "영상 나", "영상 다")
        assertEquals(2, env.autoplaySuggestions)
    }

    @Test
    fun `아니요로 거절했을 때는 규칙 휴식이 끝나면 다음 자동재생에 다시 제안한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.NO))
        fellAsleep(env)
        autoplay(env, "영상 나", "영상 다")
        assertEquals(2, env.autoplaySuggestions)
    }

}
