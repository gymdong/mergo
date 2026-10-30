package app.mergo.recipe

import app.mergo.memory.BaselineStore
import app.mergo.model.Clock
import app.mergo.service.YouTubeSignals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipesTest {
    private val yt = YouTubeSignals.PKG
    private val titleNode = { t: String -> n(text = t, viewId = "$yt:id/title", clickable = false) }

    @Test
    fun `S1 이전 동영상 버튼으로 복원되면 다음 방법은 쓰지 않는다`() = runTest {
        val screen = FakeScreen()
        val device = FakeDevice()
        var title = "오늘의 뉴스"
        val prev = n(desc = "이전 동영상")
        screen.nodeList = listOf(titleNode(title), prev)
        screen.onClick = { if (it === prev) title = "트로트 메들리" }
        val agent = FakeAgent()
        val ok = RestoreVideo(screen, device, { title }, agent).run(mapOf("title" to "트로트 메들리", "mode" to "autoplay"))
        assertTrue(ok)
        assertEquals(listOf(prev), screen.clicks)
        assertTrue("시청 기록은 열지 않는다", device.opened.isEmpty())
        assertTrue(agent.goals.isEmpty())
    }

    @Test
    fun `컨트롤이 숨어 있으면 플레이어를 탭해 이전 버튼을 띄운다`() = runTest {
        val screen = FakeScreen()
        var title = "오늘의 뉴스"
        val fullScreenContainer = n(viewId = "$yt:id/slim_status_bar_player_container", clickable = false)
        val player = n(viewId = "$yt:id/watch_player", clickable = false)
        val prev = n(desc = "이전 동영상")
        screen.nodeList = listOf(fullScreenContainer, player)
        screen.onTap = { screen.nodeList = listOf(fullScreenContainer, player, prev) }
        screen.onClick = { if (it === prev) title = "트로트 메들리" }
        assertTrue(RestoreVideo(screen, FakeDevice(), { title }, FakeAgent()).run(mapOf("title" to "트로트 메들리")))
        assertEquals(1, screen.taps.size)
    }

    // AC-R1
    @Test
    fun `이전 버튼이 없으면 시청 기록에서 같은 제목을 눌러 복원한다`() = runTest {
        val screen = FakeScreen()
        val device = FakeDevice()
        var title = "오늘의 뉴스"
        val historyItem = n(text = "트로트 메들리")
        screen.nodeList = listOf(titleNode(title))
        device.onOpenUri = { screen.nodeList = listOf(n(text = "오늘의 뉴스"), historyItem) }
        screen.onClick = { if (it === historyItem) title = "트로트 메들리" }
        val agent = FakeAgent()
        assertTrue(RestoreVideo(screen, device, { title }, agent).run(mapOf("title" to "트로트 메들리")))
        assertEquals(listOf(RestoreVideo.HISTORY_URI), device.opened)
        assertTrue(agent.goals.isEmpty())
    }

    // AC-R1
    @Test
    fun `오조작 모드는 시청 기록을 먼저 쓴다`() = runTest {
        val screen = FakeScreen()
        val device = FakeDevice()
        var title = "모르는 영상"
        val prev = n(desc = "이전 동영상")
        val historyItem = n(text = "트로트 메들리")
        screen.nodeList = listOf(prev)
        device.onOpenUri = { screen.nodeList = listOf(historyItem) }
        screen.onClick = { if (it === historyItem) title = "트로트 메들리" }
        assertTrue(RestoreVideo(screen, device, { title }, FakeAgent()).run(mapOf("title" to "트로트 메들리", "mode" to "mistouch")))
        assertEquals(listOf(historyItem), screen.clicks)
    }

    // AC-R1
    @Test
    fun `모든 방법이 실패하면 기록 화면을 닫고 에이전트까지 시도한 뒤 false`() = runTest {
        val screen = FakeScreen()
        val device = FakeDevice()
        screen.nodeList = listOf(titleNode("오늘의 뉴스"))
        device.onOpenUri = { screen.nodeList = listOf(n(text = "History"), n(text = "관계없는 영상")) }
        val agent = FakeAgent(result = false)
        val ok = RestoreVideo(screen, device, { "오늘의 뉴스" }, agent).run(mapOf("title" to "트로트 메들리"))
        assertFalse(ok)
        assertEquals(listOf("'트로트 메들리' 영상 재생"), agent.goals)
        assertEquals("기록을 열었다 못 찾으면 뒤로 가기로 닫는다", 1, screen.backs)
    }

    @Test
    fun `플레이어를 못 찾으면 아무 데나 탭하지 않고, 기록 화면이 안 열렸으면 뒤로 가기를 누르지 않는다`() = runTest {
        val screen = FakeScreen()
        val device = FakeDevice()
        // 에뮬레이터에서 본 상황: 화면 전체 컨테이너만 있고, 기록 링크를 열어도 기록 화면이 아니다(로그인 실패).
        screen.nodeList = listOf(n(viewId = "$yt:id/slim_status_bar_player_container", clickable = false))
        device.onOpenUri = { screen.nodeList = listOf(n(text = "Couldn't sign in.")) }
        assertFalse(RestoreVideo(screen, device, { "오늘의 뉴스" }, FakeAgent()).run(mapOf("title" to "트로트 메들리")))
        assertTrue(screen.taps.isEmpty())
        assertEquals(0, screen.backs)
    }

    @Test
    fun `이미 원래 영상을 보고 있으면 아무것도 누르지 않고 성공`() = runTest {
        val screen = FakeScreen()
        assertTrue(RestoreVideo(screen, FakeDevice(), { "트로트 메들리" }, FakeAgent()).run(mapOf("title" to "트로트 메들리")))
        assertTrue(screen.clicks.isEmpty() && screen.taps.isEmpty())
    }

    @Test
    fun `S4 정상 상태 볼륨이 0이면 마지막으로 소리가 나던 볼륨으로 복원한다`() = runTest {
        var now = 0L
        val store = BaselineStore(Clock { now }, emptySet())
        store.onScreen(app.mergo.model.ScreenEvent(0, "yt", null, emptyList(), false, app.mergo.model.EventKind.WINDOW_CHANGED))
        store.onDevice(volumeNow = 6, onlineNow = true, ts = 0)
        store.onDevice(volumeNow = 0, onlineNow = true, ts = 1_000, userAction = true)
        now = 10_000
        store.tick(now)
        val device = FakeDevice().apply { vol = 0 }
        assertTrue(SetVolume(device, store).run(emptyMap()))
        assertEquals(6, device.vol)
    }

    @Test
    fun `S4 기록이 전혀 없으면 최대의 절반으로 켠다`() = runTest {
        val store = BaselineStore(Clock { 0 }, emptySet())
        val device = FakeDevice().apply { vol = 0; max = 15 }
        assertTrue(SetVolume(device, store).run(emptyMap()))
        assertEquals(7, device.vol)
    }

    @Test
    fun `S3 직전 앱을 다시 연다`() = runTest {
        val store = BaselineStore(Clock { 0 }, emptySet())
        val device = FakeDevice().apply { fg = "launcher" }
        assertTrue(OpenPreviousApp(device, store).run(mapOf("pkg" to yt)))
        assertEquals(listOf(yt), device.launched)
    }

    @Test
    fun `S11 건너뛰기 버튼이 늦게 나타나면 기다렸다가 누른다`() = runTest {
        val screen = FakeScreen()
        val voice = FakeVoice()
        val skip = n(text = "광고 건너뛰기")
        screen.nodeList = listOf(n(text = "광고 · 0:05", clickable = false))
        var polls = 0
        val delayed = object : app.mergo.model.ScreenReader by screen {
            override fun nodes(): List<app.mergo.model.NodeSnapshot> {
                if (++polls == 4) screen.nodeList = listOf(skip)
                return screen.nodes()
            }
        }
        screen.onClick = { screen.nodeList = emptyList() }
        assertTrue(SkipAd(delayed, voice).run(emptyMap()))
        assertEquals(listOf(skip), screen.clicks)
        assertEquals(listOf("잠시만요, 곧 넘길 수 있어요"), voice.spoken)
    }

    @Test
    fun `S11 건너뛸 수 없는 광고면 안내만 하고 끝낸다`() = runTest {
        val screen = FakeScreen().apply { nodeList = listOf(n(text = "광고 · 0:15", clickable = false)) }
        val voice = FakeVoice()
        assertTrue(SkipAd(screen, voice).run(emptyMap()))
        assertTrue(screen.clicks.isEmpty())
        assertEquals("광고가 끝나면 이어서 나와요", voice.spoken.last())
    }
}
