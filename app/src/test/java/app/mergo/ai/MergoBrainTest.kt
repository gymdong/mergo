package app.mergo.ai

import app.mergo.model.Baseline
import app.mergo.model.BrainContext
import app.mergo.model.RecipeIds
import app.mergo.model.YesNo
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

    private fun ctx(said: String?, diff: List<String> = emptyList(), volume: Int = 8) = BrainContext(
        said, diff, emptyList(), Baseline("com.android.launcher", null, volume, true, 0), false,
    )

    @Test
    fun `후보가 하나면 LLM을 부르지 않는다`() = runTest {
        val llm = ScriptedLlm(replies = listOf(goodReply))
        val decision = MergoBrain(llm, RecordingVoice()).decide(ctx("소리가 안 나", volume = 0))
        assertEquals(RecipeIds.SET_VOLUME, decision.recipe)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `앱 이름과 열어 달라는 말이 있으면 LLM 없이 그 앱을 연다`() = runTest {
        val llm = ScriptedLlm(replies = listOf(goodReply))
        val brain = MergoBrain(llm, RecordingVoice())
        val youtube = brain.decide(ctx("유튜브 어딨어"))
        assertEquals(RecipeIds.OPEN_PREVIOUS_APP, youtube.recipe)
        assertEquals(mapOf("app" to "youtube"), youtube.params)
        assertEquals("유튜브를 열까요?", youtube.say)
        assertEquals("kakaotalk", brain.decide(ctx("카톡 좀 열어 줘")).params["app"])
        assertEquals(0, llm.calls)
    }

    @Test
    fun `앱 이름만 있고 열어 달라는 말이 없으면 앱 열기로 보지 않는다`() = runTest {
        val decision = MergoBrain(ScriptedLlm(), RecordingVoice()).decide(ctx("유튜브 소리가 안 나", volume = 0))
        assertEquals(RecipeIds.SET_VOLUME, decision.recipe)
    }

    @Test
    fun `LLM이 앱 열기에 패키지를 넣으면 버린다`() = runTest {
        val store = """{"diagnosis": "", "recipe": "open_previous_app", "params": {"pkg": "com.android.vending"}, "say": "열까요?"}"""
        val decision = MergoBrain(ScriptedLlm(replies = listOf(store)), RecordingVoice())
            .decide(ctx("그거 좀 열어 줘", listOf("기타: 변화")))
        assertEquals(RecipeIds.OPEN_PREVIOUS_APP, decision.recipe)
        assertTrue(decision.params.isEmpty())
        assertEquals("직전 앱으로 돌아가는 정해진 문장", "아까 보시던 앱으로 돌아갈까요?", decision.say)
    }

    @Test
    fun `앱 이름이 있어도 아까 보던 거를 찾으면 앱 열기가 아니라 되돌리기다`() = runTest {
        val inYouTube = BrainContext(
            "유튜브 아까 보던 거 틀어 줘", emptyList(), emptyList(),
            Baseline("com.google.android.youtube", "뉴스", 8, true, 0), false,
        )
        assertEquals(RecipeIds.RESTORE_VIDEO, MergoBrain(ScriptedLlm(), RecordingVoice()).decide(inYouTube).recipe)
    }

    @Test
    fun `앱 이름과 불평이 함께 있으면 앱을 열자고 하지 않는다`() = runTest {
        assertTrue(RecipeIds.OPEN_PREVIOUS_APP !in KeywordRules.match(ctx("카톡 들어가니까 이상해")).candidates)
    }

    @Test
    fun `존댓말로 켜 달라고 해도 앱을 연다`() = runTest {
        assertEquals("youtube", MergoBrain(ScriptedLlm(), RecordingVoice()).decide(ctx("유튜브 켜 주세요")).params["app"])
    }

    @Test
    fun `LLM이 목록에 없는 앱을 고르면 직전 앱을 열지 않고 못 한다고 말한다`() = runTest {
        val toss = """{"diagnosis": "", "recipe": "open_previous_app", "params": {"app": "toss"}, "say": "토스를 열까요?"}"""
        val decision = MergoBrain(ScriptedLlm(replies = listOf(toss)), RecordingVoice())
            .decide(ctx("그거 좀 열어 줘", listOf("기타: 변화")))
        assertEquals(RecipeIds.ASK, decision.recipe)
        assertEquals("그 앱은 제가 열어 드리기 어려워요.", decision.say)
    }

    @Test
    fun `LLM이 목록에 있는 앱을 고르면 정해진 문장으로 묻는다`() = runTest {
        val kakao = """{"diagnosis": "", "recipe": "open_previous_app", "params": {"app": "kakaotalk"}, "say": "아무 말"}"""
        val decision = MergoBrain(ScriptedLlm(replies = listOf(kakao)), RecordingVoice())
            .decide(ctx("그거 좀 열어 줘", listOf("기타: 변화")))
        assertEquals(mapOf("app" to "kakaotalk"), decision.params)
        assertEquals("카카오톡을 열까요?", decision.say)
    }

    @Test
    fun `볼륨이 켜져 있으면 소리가 안 난다고 해도 볼륨을 되돌린 척하지 않는다`() = runTest {
        val llm = ScriptedLlm(replies = listOf("""{"diagnosis": "", "recipe": "set_volume", "params": {}, "say": "켤까요?"}"""))
        val brain = MergoBrain(llm, RecordingVoice())
        val said = brain.decide(ctx("소리가 안 나"))
        assertEquals(RecipeIds.ASK, said.recipe)
        assertEquals("소리는 켜져 있어요. 영상이 멈췄는지, 이어폰이 연결됐는지 봐 주세요.", said.say)
        assertEquals(0, llm.calls)
        assertEquals("LLM이 볼륨을 골라도 막는다", RecipeIds.ASK, brain.decide(ctx("이거 이상해", listOf("기타: 변화"))).recipe)
    }

    @Test
    fun `소리와 인터넷을 함께 말하면 볼륨이 켜져 있다는 것도 알려 준다`() = runTest {
        val offline = BrainContext("소리도 안 나고 인터넷도 안 돼", emptyList(), emptyList(), Baseline("launcher", null, 8, false, 0), false)
        val decision = MergoBrain(ScriptedLlm(), RecordingVoice()).decide(offline)
        assertEquals(RecipeIds.OPEN_INTERNET_PANEL, decision.recipe)
        assertEquals("소리는 켜져 있어요. 인터넷을 다시 연결해 볼까요?", decision.say)
    }

    @Test
    fun `볼륨이 줄어든 거면 0이 아니어도 되돌린다`() = runTest {
        assertEquals(RecipeIds.SET_VOLUME, MergoBrain(ScriptedLlm(), RecordingVoice()).decide(ctx("소리가 작아졌어", listOf("볼륨: 8 → 3"), volume = 3)).recipe)
    }

    @Test
    fun `이거 이상해를 고르면 화면 변화로 후보를 고른다`() = runTest {
        val llm = ScriptedLlm(replies = listOf(goodReply))
        val decision = MergoBrain(llm, RecordingVoice()).decide(ctx("이거 이상해", listOf("볼륨: 8 → 0"), volume = 0))
        assertEquals(RecipeIds.SET_VOLUME, decision.recipe)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `이거 이상해인데 화면 변화가 없으면 범위 밖으로 자르지 않고 LLM에 묻는다`() = runTest {
        val llm = ScriptedLlm(replies = listOf(goodReply))
        val decision = MergoBrain(llm, RecordingVoice()).decide(ctx("이거 이상해"))
        assertEquals(1, llm.calls)
        assertEquals(RecipeIds.RESTORE_VIDEO, decision.recipe)
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

        val auto = MergoBrain(ScriptedLlm(), RecordingVoice(), autoExecute = { true }).decide(ctx("소리가 안 나", volume = 0))
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
        assertEquals(RecipeIds.ASK, decision.recipe)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `키가 없어 LLM이 꺼져 있어도 키워드 경로는 동작한다`() = runTest {
        val brain = MergoBrain(DisabledLlmClient(), RecordingVoice())
        assertEquals(RecipeIds.SET_VOLUME, brain.decide(ctx("소리가 안 나", volume = 0)).recipe)
        assertEquals(RecipeIds.ASK, brain.decide(ctx("이거 다시", listOf("기타: 변화"))).recipe)
    }

    @Test
    fun `후보가 여럿이고 LLM이 ask면 예아니오로 최대 2번 묻는다`() = runTest {
        val ask = """{"diagnosis": "모호", "recipe": "ask", "params": {}, "say": "무엇을 할까요?", "confirm": false}"""

        val second = RecordingVoice(YesNo.NO, YesNo.YES)
        val chosen = MergoBrain(ScriptedLlm(replies = listOf(ask)), second).decide(ctx("소리도 안 나고 인터넷도 안 돼", volume = 0))
        assertEquals(RecipeIds.OPEN_INTERNET_PANEL, chosen.recipe)
        assertFalse(chosen.confirm)
        assertEquals("인터넷을 다시 연결해 볼게요.", chosen.say)
        assertEquals(2, second.questions)

        val none = RecordingVoice(YesNo.NO, YesNo.NO)
        val gaveUp = MergoBrain(ScriptedLlm(replies = listOf(ask)), none).decide(ctx("소리도 안 나고 인터넷도 안 돼", volume = 0))
        assertEquals(RecipeIds.ASK, gaveUp.recipe)
        assertEquals(2, none.questions)
    }

    @Test
    fun `모호한 말은 화면 변화가 하나면 LLM 없이 그 변화를 되돌린다`() = runTest {
        val llm = ScriptedLlm()
        val brain = MergoBrain(llm, RecordingVoice())
        val video = ctx("이거 이상하다", listOf("영상 제목: ○○ → △△ (터치 없음)"))
        val volume = ctx("뭐가 잘못됐나", listOf("볼륨: 8 → 0"), volume = 0)
        assertEquals(RecipeIds.RESTORE_VIDEO, brain.decide(video).recipe)
        assertEquals(RecipeIds.SET_VOLUME, brain.decide(volume).recipe)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `근거 없는 발화에는 LLM이 지어낸 약속을 말하지 않는다`() = runTest {
        val promise = """{"diagnosis": "노래", "recipe": "ask", "params": {}, "say": "어떤 노래를 들려드릴까요?", "confirm": true}"""
        val decision = MergoBrain(ScriptedLlm(replies = listOf(promise)), RecordingVoice()).decide(ctx("노래 한 곡 불러 줘"))
        assertEquals(RecipeIds.ASK, decision.recipe)
        assertEquals("그건 제가 해 드리기 어려워요. 화면에 문제가 있으신가요?", decision.say)
    }

    @Test
    fun `도와줘 선택지 4개는 LLM 없이 맞힌다`() = runTest {
        val llm = ScriptedLlm()
        val brain = MergoBrain(llm, RecordingVoice())
        fun at(pkg: String, said: String, volume: Int = 8) =
            BrainContext(said, emptyList(), emptyList(), Baseline(pkg, null, volume, true, 0), false)
        val youtube = "com.google.android.youtube"

        assertEquals(RecipeIds.RESTORE_VIDEO, brain.decide(at(youtube, "아까 보던 거")).recipe)
        assertEquals(RecipeIds.OPEN_PREVIOUS_APP, brain.decide(at("launcher", "아까 보던 거")).recipe)
        assertEquals(RecipeIds.SET_VOLUME, brain.decide(at(youtube, "소리가 안 나", volume = 0)).recipe)
        assertEquals("광고 닫기", brain.decide(at("com.android.chrome", "이거 안 꺼져")).params["goal"])
        assertEquals(RecipeIds.SKIP_AD, brain.decide(at(youtube, "광고 안 꺼져")).recipe)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `금지 발화는 LLM에 묻지 않고 바로 ask로 답한다`() = runTest {
        val llm = ScriptedLlm(replies = listOf(goodReply))
        val decision = MergoBrain(llm, RecordingVoice()).decide(ctx("아까 그 돈 보내 줘"))
        assertEquals(RecipeIds.ASK, decision.recipe)
        assertEquals(0, llm.calls)
    }
}
