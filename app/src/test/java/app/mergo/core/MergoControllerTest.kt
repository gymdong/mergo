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
import app.mergo.model.FailReason
import app.mergo.model.Marker
import app.mergo.model.ScreenEvent
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.RecipeOutcome
import app.mergo.model.YesNo
import app.mergo.recipe.FakeVoice
import app.mergo.ui.OverlayPort
import app.mergo.ui.Prompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val chosenPrompts = mutableListOf<String>()
        override suspend fun choose(prompt: String, choices: List<String>, timeoutMs: Long): String? {
            chosenPrompts += prompt
            val c = choice ?: run { delay(timeoutMs); return null }
            delay(answerAfterMs)
            return c
        }
        override fun progress(text: String?) { progress += text }
        override fun done(title: String, body: String) { done += title }
        val notices = mutableListOf<String>()
        override fun notice(text: String) { notices += text }
        override fun dismiss() {}
    }

    private class FakeRecipe(override val id: String, val outcome: RecipeOutcome) : Recipe {
        val calls = mutableListOf<Map<String, String>>()
        override suspend fun run(params: Map<String, String>): RecipeOutcome { calls += params; return outcome }
    }

    private class Env(
        scope: TestScope,
        outcome: RecipeOutcome = RecipeOutcome.Verified(),
        val overlay: FakeOverlay = FakeOverlay(),
        val voice: FakeVoice = FakeVoice(),
        autoExecute: Boolean = false,
        decision: BrainDecision? = null,
    ) {
        val clock = Clock { scope.testScheduler.currentTime }
        val cooldown = Cooldown()
        val baseline = BaselineStore(clock, emptySet())
        val detector = StuckDetector(clock, DetectorConfig(), cooldown) { baseline.current?.mediaTitle }
        val recipe = FakeRecipe(RecipeIds.RESTORE_VIDEO, outcome)
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
            liveVolume = { 8 }, liveOnline = { true }, liveScreen = { liveScreen },
        )
        var liveScreen = LiveScreen(null, emptyList())
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
    fun `안내만 하고 끝난 복구는 해결로도 실패로도 세지 않고 가족 알림을 묻지 않는다`() = runTest {
        val env = Env(this, outcome = RecipeOutcome.Guided("광고가 끝나면 이어서 나와요"), overlay = FakeOverlay(answer = YesNo.YES))
        env.controller.onSuggestion(env.suggestion)
        assertEquals(listOf("영상이 바뀐 것 같아요"), env.overlay.asked)
        assertTrue("완료라고 말하지 않는다", env.overlay.done.isEmpty())
        val log = env.familyLog.all().single()
        assertTrue(!log.resolved && !log.notified)
        assertEquals("guided", log.outcome)
        assertEquals("광고가 끝나면 이어서 나와요", log.summary)
        assertEquals("안내 문장은 컨트롤러가 한 번 말한다", 1, env.voice.spoken.count { it == "광고가 끝나면 이어서 나와요" })
        assertEquals("글자로도 보여 준다", listOf("광고가 끝나면 이어서 나와요"), env.overlay.notices)
    }

    @Test
    fun `검증 불가 복구는 완료라고 하지 않고, 확인 질문에 답이 없으면 미확인으로 남긴다`() = runTest {
        val env = Env(
            this, outcome = RecipeOutcome.Unverifiable("에이전트 완료 선언만 있음"),
            overlay = FakeOverlay(answer = YesNo.YES, answers = mapOf("이 화면이 맞나요?" to YesNo.NONE)),
        )
        env.controller.onSuggestion(env.suggestion)
        assertTrue(env.overlay.done.isEmpty())
        assertTrue("제대로 됐는지 봐 주세요" in env.voice.spoken)
        assertTrue("가족 알림은 묻지 않는다", "제가 해결하지 못했어요" !in env.overlay.asked)
        val log = env.familyLog.all().single()
        assertTrue(!log.resolved && !log.notified)
        assertEquals("unverifiable", log.outcome)
    }

    @Test
    fun `검증 불가 복구도 사용자가 맞다고 하면 해결로 기록한다`() = runTest {
        val env = Env(
            this, outcome = RecipeOutcome.Unverifiable("에이전트 완료 선언만 있음"),
            overlay = FakeOverlay(answer = YesNo.YES),
        )
        env.controller.onSuggestion(env.suggestion)
        val log = env.familyLog.all().single()
        assertTrue(log.resolved)
        assertEquals("학습 라벨은 검증 불가 그대로", "unverifiable", log.outcome)
    }

    @Test
    fun `검증 불가 복구를 아니라고 하면 가족 알림을 묻는다`() = runTest {
        val env = Env(
            this, outcome = RecipeOutcome.Unverifiable("에이전트 완료 선언만 있음"),
            overlay = FakeOverlay(answer = YesNo.YES, answers = mapOf("이 화면이 맞나요?" to YesNo.NO)),
        )
        env.controller.onSuggestion(env.suggestion)
        assertTrue("제가 해결하지 못했어요" in env.overlay.asked)
        assertEquals(false, env.familyLog.all().single().resolved)
    }

    @Test
    fun `머고가 확인했는데 사용자가 아니라고 하면 따로 표시해 남긴다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, answers = mapOf("이 화면이 맞나요?" to YesNo.NO)))
        env.controller.onSuggestion(env.suggestion)
        assertEquals("verified_rejected", env.familyLog.all().single().outcome)
    }

    @Test
    fun `확인 없이 실행 모드에서 검증 불가면 묻지도 말하지도 않고 미확인으로 남긴다`() = runTest {
        val env = Env(this, outcome = RecipeOutcome.Unverifiable("에이전트 완료 선언만 있음"), autoExecute = true)
        env.controller.onSuggestion(env.suggestion)
        assertTrue(env.overlay.asked.isEmpty())
        assertTrue("제대로 됐는지 봐 주세요" !in env.voice.spoken)
        val log = env.familyLog.all().single()
        assertTrue(!log.resolved && !log.notified)
    }

    @Test
    fun `등록되지 않은 레시피를 고르면 실패로 기록한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES))
        env.controller.onSuggestion(env.suggestion.copy(recipeId = RecipeIds.OPEN_INTERNET_PANEL))
        assertEquals("failed:no_recipe", env.familyLog.all().single().outcome)
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
        val env = Env(this, outcome = RecipeOutcome.Failed(FailReason.NOT_REACHED), overlay = FakeOverlay(answer = YesNo.YES))
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

    /** 영상 가를 35초 본 뒤(정상 상태) 직접 영상 나를 골라, 지금 화면 제목만 바뀐 상황. */
    private suspend fun TestScope.videoChanged(env: Env) {
        env.baseline.onDevice(8, true, 0)
        env.play(1_000, "영상 가")
        repeat(40) { delay(1_000); env.controller.tick() }
        env.play(testScheduler.currentTime, "영상 나")
        env.controller.tick()
    }

    @Test
    fun `도와줘를 누르면 짐작 가는 것 하나를 먼저 묻고, 응이면 선택지 없이 바로 되돌린다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES))
        videoChanged(env)
        env.controller.onHelp()
        assertEquals("보던 거 다시 틀까요?", env.overlay.asked.first())
        assertEquals(listOf(mapOf("title" to "영상 가")), env.recipe.calls)
        assertTrue("판단을 다시 하지 않는다", env.brainCalls.isEmpty())
    }

    @Test
    fun `짐작이 아니라고 하면 선택지를 보여 주고 고른 것으로 판단한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, choice = "소리가 안 나", answers = mapOf("보던 거 다시 틀까요?" to YesNo.NO)))
        videoChanged(env)
        env.controller.onHelp()
        assertEquals("소리가 안 나", env.brainCalls.single().utterance)
    }

    @Test
    fun `짐작에 답이 없으면 선택지도 띄우지 않고 닫는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = null, choice = "소리가 안 나"))
        videoChanged(env)
        env.controller.onHelp()
        assertTrue(env.recipe.calls.isEmpty() && env.brainCalls.isEmpty())
    }

    @Test
    fun `바뀐 게 없으면 짐작 없이 바로 선택지를 보여 준다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, choice = "아까 보던 거"))
        env.baseline.onDevice(8, true, 0)
        env.play(1_000, "영상 가")
        repeat(40) { delay(1_000); env.controller.tick() }
        env.controller.onHelp()
        assertTrue("보던 거 다시 틀까요?" !in env.overlay.asked)
        assertEquals("아까 보던 거", env.brainCalls.single().utterance)
    }

    @Test
    fun `스토어 화면에서 도와줘를 부르면 아무것도 누르지 않고 빠져나오는 법만 알려 준다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, choice = "이거 안 꺼져"))
        env.liveScreen = LiveScreen("com.android.vending", listOf("설치"))
        env.controller.onHelp()
        assertEquals(listOf("여기서는 누르지 않을게요"), env.overlay.asked)
        assertTrue(env.brainCalls.isEmpty() && env.recipe.calls.isEmpty())
        val log = env.familyLog.all().single()
        assertEquals("guided", log.outcome)
        assertTrue(log.notified && !log.resolved)
    }

    @Test
    fun `카드번호를 묻는 웹페이지에서도 누르지 않는다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.NO, choice = "이거 안 꺼져"))
        env.liveScreen = LiveScreen("com.android.chrome", listOf("택배 주소 확인", "카드 번호 입력"))
        env.controller.onHelp("이거 뭐야")
        assertTrue(env.brainCalls.isEmpty())
        assertEquals(false, env.familyLog.all().single().notified)
    }

    @Test
    fun `평범한 웹페이지면 그대로 판단한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, choice = "이거 안 꺼져"))
        env.liveScreen = LiveScreen("com.android.chrome", listOf("우리 동네 소식"))
        env.controller.onHelp()
        assertEquals("이거 안 꺼져", env.brainCalls.single().utterance)
    }

    @Test
    fun `와이파이 설정의 비밀번호나 카톡의 인증번호 글자만으로는 민감 화면으로 보지 않는다`() {
        assertFalse(SensitiveScreen.matches(LiveScreen("com.android.settings", listOf("Wi‑Fi", "비밀번호 저장됨"))))
        assertFalse(SensitiveScreen.matches(LiveScreen("com.kakao.talk", listOf("[Web발신] 인증번호 [123456]"))))
        assertTrue(SensitiveScreen.matches(LiveScreen("com.android.chrome", listOf("결제하기"))))
    }

    @Test
    fun `되물으면 그 질문으로 선택지를 한 번 더 보여 주고 고른 것으로 다시 판단한다`() = runTest {
        val env = Env(this, overlay = FakeOverlay(answer = YesNo.YES, choice = "이거 이상해"))
        val asks = ArrayDeque(listOf(
            BrainDecision("모름", RecipeIds.ASK, emptyMap(), "화면이 어떻게 이상하신가요?", false),
            BrainDecision("영상", RecipeIds.RESTORE_VIDEO, emptyMap(), "돌아갈까요?", true),
        ))
        val controller = MergoController(
            this, env.clock, TimelineBuffer(env.clock), env.baseline, env.detector, env.cooldown,
            mapOf(env.recipe.id to env.recipe),
            object : Brain { override suspend fun decide(ctx: BrainContext) = asks.removeFirst() },
            env.voice, env.overlay, DefaultSettings(), env.familyLog,
            liveVolume = { 8 }, liveOnline = { true },
        )
        controller.onHelp()
        assertEquals(listOf("무엇을 도와드릴까요?", "화면이 어떻게 이상하신가요?"), env.overlay.chosenPrompts)
        assertEquals(1, env.recipe.calls.size)
    }

    @Test
    fun `판단하는 동안 살펴보고 있다고 보여 주고, 되묻는 말은 글자로도 보여 준다`() = runTest {
        val env = Env(
            this, overlay = FakeOverlay(choice = "이거 이상해"),
            decision = BrainDecision("판단 불가", RecipeIds.ASK, emptyMap(), "제가 잘 못 알아들었어요.", false),
        )
        env.controller.onHelp()
        assertEquals("두 번 판단할 때마다 보여 준다", listOf<String?>("살펴보고 있어요", null, "살펴보고 있어요", null), env.overlay.progress)
        assertEquals("두 번째도 모르면 글자로 남기고 끝낸다", listOf("제가 잘 못 알아들었어요."), env.overlay.notices)
        assertEquals(2, env.overlay.chosenPrompts.size)
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
