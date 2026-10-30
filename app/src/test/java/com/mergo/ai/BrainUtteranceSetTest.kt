package com.mergo.ai

import com.mergo.model.Baseline
import com.mergo.model.BrainContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC-B5: 발화 테스트셋 38개. 시나리오별 5개(그중 2개는 경상도 사투리), 범위 밖 5개, S11 3개. */
class BrainUtteranceSetTest {
    private data class Case(val scenario: String, val said: String, val ctx: BrainContext, val expected: String)

    private val youtube = "com.google.android.youtube"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val chrome = "com.android.chrome"

    private fun ctx(
        pkg: String,
        diff: List<String> = emptyList(),
        volume: Int = 8,
        online: Boolean = true,
        ad: Boolean = false,
    ) = { said: String ->
        BrainContext(said, diff, emptyList(), Baseline(pkg, "△△ 뉴스", volume, online, 0), ad)
    }

    private fun scenario(
        name: String,
        expected: String,
        context: (String) -> BrainContext,
        vararg said: String,
    ) = said.map { Case(name, it, context(it), expected) }

    private val autoplay = ctx(youtube, listOf("영상 제목: ○○ 트로트 메들리 → △△ 뉴스 (터치 없음)"))
    private val mistap = ctx(youtube, listOf("영상 제목: ○○ 트로트 메들리 → △△ 뉴스 (터치 있음)"))
    private val leftApp = ctx(launcher, listOf("앱: 유튜브 → 홈 화면"))
    private val muted = ctx(youtube, listOf("볼륨: 8 → 0"), volume = 0)
    private val popup = ctx(chrome)
    private val offline = ctx(launcher, listOf("연결: 켜짐 → 꺼짐"), online = false)
    private val adShown = ctx(youtube, ad = true)
    private val idle = ctx(launcher)

    private val cases =
        scenario("S1", RecipeId.RESTORE_VIDEO, autoplay,
            "아까 보던 거", "아까 보던 영상 틀어 줘", "이전 영상으로 돌아가 줘", "이거 와이라노", "뭐고 이거 아까 그거 아이가") +
            scenario("S2", RecipeId.RESTORE_VIDEO, mistap,
                "이거 아닌데", "잘못 눌렀어", "이게 아닌데", "아이다 이거 아이다", "잘못 눌렀다 아이가") +
            scenario("S3", RecipeId.OPEN_PREVIOUS_APP, leftApp,
                "아까 그거 어디 갔어", "유튜브 어디 갔어", "보던 거 없어졌어", "아까 그거 어데 갔노", "유튜브 우째 없어졌노") +
            scenario("S4", RecipeId.SET_VOLUME, muted,
                "소리가 안 나", "소리 좀 켜 줘", "왜 소리가 안 들려", "소리가 와 안 나노", "소리 안 들린다 카이") +
            scenario("S6", RecipeId.SCREEN_AGENT, popup,
                "이거 안 꺼져", "광고 창이 안 닫혀", "엑스를 못 누르겠어", "이거 와 안 꺼지노", "이거 우째 끄노") +
            scenario("S8", RecipeId.OPEN_INTERNET_PANEL, offline,
                "유튜브가 안 나와", "인터넷이 안 돼", "와이파이 켜 줘", "유튜브가 와 안 나오노", "인터넷 와 안 되노") +
            scenario("범위 밖", RecipeId.ASK, idle,
                "오늘 날씨 어때", "엄마한테 전화해 줘", "택시 불러 줘", "돈 좀 보내 줘", "노래 한 곡 불러 줘") +
            scenario("S11", RecipeId.SKIP_AD, adShown,
                "광고 넘겨 줘", "광고 안 꺼져", "이 광고 와 안 넘어가노")

    @Test
    fun `정답률이 90퍼센트 이상이고 범위 밖 발화에는 레시피를 실행하지 않는다`() = runTest {
        val brain = MergoBrain(WrongGuessLlm(), RecordingVoice())
        val wrong = mutableListOf<String>()
        var unsafe = 0

        for (case in cases) {
            val got = brain.decide(case.ctx).recipe
            if (got != case.expected) wrong += "${case.scenario} '${case.said}': $got (기대 ${case.expected})"
            if (case.scenario == "범위 밖" && got != RecipeId.ASK) unsafe++
        }

        assertEquals(38, cases.size)
        assertEquals("범위 밖 발화에 레시피 실행", 0, unsafe)
        val passed = cases.size - wrong.size
        println("발화 테스트셋 정답 $passed/${cases.size}")
        wrong.forEach(::println)
        assertTrue("정답 $passed/${cases.size}\n${wrong.joinToString("\n")}", passed >= 35)
    }
}
