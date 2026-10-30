package app.mergo.detect

import app.mergo.model.EventKind
import app.mergo.model.Marker
import app.mergo.model.RecipeIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckDetectorTest {

    private fun autoplay(sim: Sim, at: Long, next: String, countdown: Boolean) {
        if (countdown) {
            sim.event(at - 5_000, EventKind.CONTENT_CHANGED, title = "트로트 메들리",
                markers = setOf(Marker.VIDEO_PLAYER, Marker.AUTOPLAY_COUNTDOWN))
        }
        sim.event(at, EventKind.CONTENT_CHANGED, title = next, markers = setOf(Marker.VIDEO_PLAYER))
    }

    // AC-D1
    @Test
    fun `S1 카운트다운 뒤 자동재생이면 10초 뒤 R1 제안이 정확히 1회`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        autoplay(sim, 70_000, "오늘의 뉴스", countdown = true)
        sim.advanceTo(79_000)
        assertTrue("10초 전에는 제안하지 않는다", sim.suggestions.isEmpty())
        sim.playing(79_000, 120_000, "오늘의 뉴스")
        assertEquals(listOf(RuleId.R1_AUTOPLAY), sim.rules())
        val s = sim.suggestions.single()
        assertEquals(RecipeIds.RESTORE_VIDEO, s.recipeId)
        assertEquals("트로트 메들리", s.params["title"])
        assertEquals("autoplay", s.params["mode"])
    }

    // AC-D2
    @Test
    fun `카운트다운이 없으면 카운트다운 우선 모드에서는 제안하지 않는다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        autoplay(sim, 70_000, "오늘의 뉴스", countdown = false)
        sim.playing(70_000, 120_000, "오늘의 뉴스")
        assertTrue(sim.suggestions.isEmpty())
    }

    // AC-D2
    @Test
    fun `대체 모드에서는 카운트다운 없이 터치 부재만으로 R1 제안 1회`() {
        val sim = Sim(DetectorConfig(launcherPkgs = setOf(LAUNCHER), requireCountdown = false, suggestAutoplay = true))
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        autoplay(sim, 70_000, "오늘의 뉴스", countdown = false)
        sim.playing(70_000, 120_000, "오늘의 뉴스")
        assertEquals(listOf(RuleId.R1_AUTOPLAY), sim.rules())
    }

    @Test
    fun `에뮬레이터 실측 - 탭 이벤트 없이 다음 영상으로 넘겨도 정상 상태가 되고, 자동재생 직후 YouTube 자체 스크롤은 R1을 취소하지 않는다`() {
        val sim = Sim()
        // 사용자가 플레이어의 '다음 영상'을 눌렀지만 클릭 이벤트는 오지 않는다.
        sim.event(1_000, EventKind.CONTENT_CHANGED, title = "트로트 메들리", markers = setOf(Marker.VIDEO_PLAYER))
        sim.event(1_300, EventKind.SCROLL)
        sim.playing(1_000, 60_000, "트로트 메들리")
        assertEquals("트로트 메들리", sim.baseline.current?.mediaTitle)
        autoplay(sim, 70_000, "오늘의 뉴스", countdown = true)
        sim.event(70_017, EventKind.SCROLL)   // YouTube가 새 영상에 맞춰 목록을 스스로 스크롤
        sim.playing(70_017, 100_000, "오늘의 뉴스")
        assertEquals(listOf(RuleId.R1_AUTOPLAY), sim.rules())
    }

    @Test
    fun `자동재생 뒤 10초 안에 사용자가 움직이면 R1을 취소한다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        autoplay(sim, 70_000, "오늘의 뉴스", countdown = true)
        sim.event(75_000, EventKind.TOUCH)
        sim.playing(75_000, 120_000, "오늘의 뉴스")
        assertTrue(sim.suggestions.isEmpty())
    }

    @Test
    fun `30초 미만 본 영상은 정상 상태가 아니라서 R1 대상이 아니다`() {
        val sim = Sim()
        sim.userPlays(0, "짧게 본 영상", watchMs = 20_000)
        autoplay(sim, 28_000, "다른 영상", countdown = true)
        sim.playing(28_000, 60_000, "다른 영상")
        assertTrue(sim.suggestions.isEmpty())
    }

    @Test
    fun `S2 잘못 누른 뒤 여기저기 3번 연달아 누르면 R3 제안`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(70_000, EventKind.CLICK)
        sim.event(71_000, EventKind.CONTENT_CHANGED, title = "모르는 영상", markers = setOf(Marker.VIDEO_PLAYER))
        sim.event(80_000, EventKind.CLICK)
        sim.event(81_000, EventKind.CLICK)
        sim.event(82_000, EventKind.CLICK)
        sim.advanceTo(130_000)
        assertEquals(listOf(RuleId.R3_MISTOUCH), sim.rules())
    }

    @Test
    fun `일부러 고른 영상에서 가끔 누르는 건 연타가 아니다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(70_000, EventKind.CLICK)
        sim.event(71_000, EventKind.CONTENT_CHANGED, title = "고른 영상", markers = setOf(Marker.VIDEO_PLAYER))
        sim.event(80_000, EventKind.CLICK)
        sim.event(85_000, EventKind.CLICK)
        sim.event(90_000, EventKind.CLICK)
        sim.advanceTo(130_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    @Test
    fun `R5 유튜브에서 누른 직후 브라우저가 열려 5초 머물면 보던 화면으로 돌아갈지 묻는다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        sim.event(50_000, EventKind.CLICK)
        sim.event(50_500, EventKind.WINDOW_CHANGED, pkg = "com.android.chrome")
        sim.advanceTo(60_000)
        assertEquals(listOf(RuleId.R5_LINK_EXIT), sim.rules())
        assertEquals(mapOf("pkg" to YT, "via" to "back"), sim.suggestions.single().params)
    }

    @Test
    fun `R5 곧바로 유튜브로 돌아오면 묻지 않는다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        sim.event(50_000, EventKind.CLICK)
        sim.event(50_500, EventKind.WINDOW_CHANGED, pkg = "com.android.chrome")
        sim.event(52_000, EventKind.WINDOW_CHANGED, pkg = YT)
        sim.advanceTo(60_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    @Test
    fun `R5 카톡에서 링크를 눌러 브라우저가 열린 건 일부러 연 것으로 본다`() {
        val sim = Sim()
        sim.event(10_000, EventKind.WINDOW_CHANGED, pkg = "com.kakao.talk")
        sim.event(50_000, EventKind.CLICK, pkg = "com.kakao.talk")
        sim.event(50_500, EventKind.WINDOW_CHANGED, pkg = "com.android.chrome")
        sim.advanceTo(60_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    @Test
    fun `R5 누르지 않았는데 브라우저가 뜬 건 오조작으로 보지 않는다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        sim.event(50_500, EventKind.WINDOW_CHANGED, pkg = "com.android.chrome")
        sim.advanceTo(60_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    // AC-D3
    @Test
    fun `S2 잘못 누른 뒤 3초 안에 뒤로 가기 2회면 R3 제안 1회`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(70_000, EventKind.CLICK)
        sim.event(71_000, EventKind.CONTENT_CHANGED, title = "모르는 영상", markers = setOf(Marker.VIDEO_PLAYER))
        sim.event(75_000, EventKind.BACK)
        sim.event(76_500, EventKind.BACK)
        sim.advanceTo(130_000)
        assertEquals(listOf(RuleId.R3_MISTOUCH), sim.rules())
        assertEquals("트로트 메들리", sim.suggestions.single().params["title"])
        assertEquals("mistouch", sim.suggestions.single().params["mode"])
    }

    @Test
    fun `뒤로 가기가 3초 간격을 넘으면 연타가 아니다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(70_000, EventKind.CLICK)
        sim.event(71_000, EventKind.CONTENT_CHANGED, title = "모르는 영상", markers = setOf(Marker.VIDEO_PLAYER))
        sim.event(75_000, EventKind.BACK)
        sim.event(80_000, EventKind.BACK)
        sim.advanceTo(130_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    // AC-D4
    @Test
    fun `S3 재생 중 홈으로 나가 20초 머물면 R2 제안 1회`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(62_000, EventKind.KEY)
        sim.event(62_100, EventKind.WINDOW_CHANGED, pkg = LAUNCHER)
        sim.advanceTo(81_000)
        assertTrue("20초 전에는 제안하지 않는다", sim.suggestions.isEmpty())
        sim.advanceTo(100_000)
        assertEquals(listOf(RuleId.R2_HOME_EXIT), sim.rules())
        assertEquals(RecipeIds.OPEN_PREVIOUS_APP, sim.suggestions.single().recipeId)
        assertEquals(YT, sim.suggestions.single().params["pkg"])
    }

    @Test
    fun `홈에서 20초 안에 다른 앱을 열면 R2를 취소한다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(62_100, EventKind.WINDOW_CHANGED, pkg = LAUNCHER)
        sim.event(70_000, EventKind.CLICK, pkg = LAUNCHER)
        sim.event(70_500, EventKind.WINDOW_CHANGED, pkg = "com.kakao.talk")
        sim.advanceTo(120_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    // AC-D4
    @Test
    fun `오조작 뒤 홈으로 나가면 R2가 아니라 R3만 1회`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(70_000, EventKind.CLICK)
        sim.event(71_000, EventKind.CONTENT_CHANGED, title = "모르는 영상", markers = setOf(Marker.VIDEO_PLAYER))
        sim.event(80_000, EventKind.WINDOW_CHANGED, pkg = LAUNCHER)
        sim.advanceTo(130_000)
        assertEquals(listOf(RuleId.R3_MISTOUCH), sim.rules())
    }

    // AC-D5 N1
    @Test
    fun `N1 직접 고른 영상을 계속 보면 제안 0회`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(70_000, EventKind.CLICK)
        sim.event(71_000, EventKind.CONTENT_CHANGED, title = "골라 본 영상", markers = setOf(Marker.VIDEO_PLAYER))
        sim.playing(71_000, 180_000, "골라 본 영상")
        assertTrue(sim.suggestions.isEmpty())
        assertEquals("30초 넘게 본 영상은 새 정상 상태가 된다", "골라 본 영상", sim.baseline.current?.mediaTitle)
    }

    // AC-D5 N2
    @Test
    fun `N2 설정 화면에서 60초 멈춰 있어도 제안 0회`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        sim.event(45_000, EventKind.CLICK)
        sim.event(45_500, EventKind.WINDOW_CHANGED, pkg = SETTINGS, title = "디스플레이")
        sim.advanceTo(110_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    // AC-D5 N3
    @Test
    fun `N3 재생 중 볼륨 키로 0을 만들어도 제안 0회`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        repeat(8) { i -> sim.event(45_000L + i * 300, EventKind.KEY) }
        sim.baseline.onDevice(volumeNow = 0, onlineNow = true, ts = 47_400, userAction = true)
        sim.playing(47_400, 120_000, "트로트 메들리")
        assertTrue(sim.suggestions.isEmpty())
    }

    // AC-D8
    @Test
    fun `R4 건너뛰기 버튼이 5초 보이고 무동작이면 제안 1회`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        adVisible(sim, 50_000, 70_000)
        assertEquals(listOf(RuleId.R4_AD_SKIP), sim.rules())
        assertEquals(RecipeIds.SKIP_AD, sim.suggestions.single().recipeId)
        assertEquals("기본은 묻지 않고 넘긴다", false, sim.suggestions.single().askFirst)
    }

    @Test
    fun `기본 설정에서는 자동재생으로 넘어가도 되돌리기를 제안하지 않는다`() {
        val sim = Sim(DetectorConfig(launcherPkgs = setOf(LAUNCHER)))
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        sim.event(50_000, EventKind.CONTENT_CHANGED, title = "트로트 메들리", markers = setOf(Marker.AUTOPLAY_COUNTDOWN))
        sim.event(55_000, EventKind.CONTENT_CHANGED, title = "다른 영상")
        sim.advanceTo(80_000)
        assertTrue(sim.rules().isEmpty())
    }

    @Test
    fun `R4 광고가 보인 뒤 사용자가 화면을 만지면 제안하지 않는다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        sim.event(50_000, EventKind.CONTENT_CHANGED, title = "트로트 메들리", markers = setOf(Marker.AD_SKIP_VISIBLE))
        sim.event(51_000, EventKind.TOUCH)
        adVisible(sim, 51_500, 70_000)
        // 새 광고로 보지 않으므로 처음 본 시점(50초) 뒤 터치가 있어 제안하지 않는다.
        assertTrue(sim.suggestions.isEmpty())
    }

    // AC-D5 N4 + AC-D6
    @Test
    fun `N4 광고 제안을 거절하면 같은 광고에 다시 제안하지 않는다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 40_000)
        adVisible(sim, 50_000, 60_000)
        val first = sim.suggestions.single()
        sim.cooldown.reject(first.rule, first.target, sim.now)
        sim.event(61_000, EventKind.CONTENT_CHANGED, title = "트로트 메들리")
        adVisible(sim, 65_000, 90_000)
        assertEquals(1, sim.suggestions.size)
    }

    // AC-D6
    @Test
    fun `거절한 규칙은 5분 동안 다른 대상에도 제안하지 않고 그 뒤에는 다시 제안한다`() {
        val sim = Sim(DetectorConfig(launcherPkgs = setOf(LAUNCHER), requireCountdown = false, suggestAutoplay = true))
        sim.userPlays(0, "영상 가", watchMs = 60_000)
        autoplay(sim, 70_000, "영상 나", countdown = false)
        sim.advanceTo(81_000)
        val s = sim.suggestions.single()
        sim.cooldown.reject(s.rule, s.target, sim.now)
        sim.baseline.acceptCurrent(sim.now)
        // 거절한 상태가 정상 상태가 된다.
        assertEquals("영상 나", sim.baseline.current?.mediaTitle)

        autoplay(sim, 120_000, "영상 다", countdown = false)
        sim.advanceTo(140_000)
        assertEquals("5분 안에는 다른 대상도 쉰다", 1, sim.suggestions.size)

        sim.baseline.acceptCurrent(sim.now)
        autoplay(sim, 400_000, "영상 라", countdown = false)
        sim.advanceTo(420_000)
        assertEquals(2, sim.suggestions.size)
        assertEquals("영상 다", sim.suggestions.last().params["title"])
    }

    @Test
    fun `말풍선이 떠 있는 동안에는 새 제안을 내지 않는다`() {
        val sim = Sim()
        sim.detector.suggestionShowing = true
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        autoplay(sim, 70_000, "오늘의 뉴스", countdown = true)
        sim.advanceTo(100_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    @Test
    fun `말풍선 때문에 미뤄진 제안은 말풍선이 닫히면 다시 낸다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        autoplay(sim, 70_000, "오늘의 뉴스", countdown = true)
        sim.detector.suggestionShowing = true
        sim.advanceTo(85_000)
        assertTrue(sim.suggestions.isEmpty())
        sim.detector.suggestionShowing = false
        sim.advanceTo(86_000)
        assertEquals(listOf(RuleId.R1_AUTOPLAY), sim.rules())
    }

    @Test
    fun `일시정지 상태로 제목만 있던 YouTube에서 홈으로 나가면 R2가 아니다`() {
        val sim = Sim()
        sim.event(0, EventKind.CLICK)
        sim.event(1_000, EventKind.CONTENT_CHANGED, title = "트로트 메들리")
        sim.event(5_000, EventKind.WINDOW_CHANGED, pkg = LAUNCHER)
        sim.advanceTo(60_000)
        assertTrue(sim.suggestions.isEmpty())
    }

    @Test
    fun `에뮬레이터 실측 - 복구 직후 늦게 바뀐 제목은 오조작으로 보지 않고, 그 뒤 홈으로 나가면 R2`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        autoplay(sim, 70_000, "고양이 영상", countdown = true)
        sim.advanceTo(80_000)
        assertEquals(listOf(RuleId.R1_AUTOPLAY), sim.rules())
        // 머고가 이전 영상으로 되돌렸고, 제목은 1초 늦게 들어온다.
        sim.detector.afterAutomation(sim.now)
        sim.event(81_000, EventKind.CONTENT_CHANGED, title = "트로트 메들리", markers = setOf(Marker.VIDEO_PLAYER))
        sim.event(90_000, EventKind.WINDOW_CHANGED, pkg = LAUNCHER)
        sim.advanceTo(115_000)
        assertEquals(listOf(RuleId.R1_AUTOPLAY, RuleId.R2_HOME_EXIT), sim.rules())
    }

    @Test
    fun `머고 자신과 시스템 UI 이벤트는 감지에서 뺀다`() {
        val sim = Sim()
        sim.userPlays(0, "트로트 메들리", watchMs = 60_000)
        sim.event(62_100, EventKind.WINDOW_CHANGED, pkg = LAUNCHER)
        sim.event(65_000, EventKind.WINDOW_CHANGED, pkg = "com.android.systemui")
        sim.event(66_000, EventKind.CLICK, pkg = "app.mergo")
        sim.advanceTo(90_000)
        assertEquals(listOf(RuleId.R2_HOME_EXIT), sim.rules())
    }

    private fun adVisible(sim: Sim, from: Long, to: Long) {
        var t = from
        while (t <= to) {
            sim.event(t, EventKind.CONTENT_CHANGED, title = "트로트 메들리",
                markers = setOf(Marker.AD_SHOWING, Marker.AD_SKIP_VISIBLE))
            t += 1_000
        }
    }
}
