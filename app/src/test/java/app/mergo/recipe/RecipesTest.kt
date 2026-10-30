package app.mergo.recipe

import app.mergo.memory.BaselineStore
import app.mergo.model.Clock
import app.mergo.model.FailReason
import app.mergo.model.RecipeOutcome
import app.mergo.service.YouTubeSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        val outcome = RestoreVideo(screen, device, { title }, agent).run(mapOf("title" to "트로트 메들리", "mode" to "autoplay"))
        assertEquals(RecipeOutcome.Verified(), outcome)
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
        assertEquals(RecipeOutcome.Verified(), RestoreVideo(screen, FakeDevice(), { title }, FakeAgent()).run(mapOf("title" to "트로트 메들리")))
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
        assertEquals(RecipeOutcome.Verified(), RestoreVideo(screen, device, { title }, agent).run(mapOf("title" to "트로트 메들리")))
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
        assertEquals(RecipeOutcome.Verified(), RestoreVideo(screen, device, { title }, FakeAgent()).run(mapOf("title" to "트로트 메들리", "mode" to "mistouch")))
        assertEquals(listOf(historyItem), screen.clicks)
    }

    // AC-R1
    @Test
    fun `모든 방법이 실패하면 기록 화면을 닫고 에이전트까지 시도한 뒤 목표 미도달로 실패`() = runTest {
        val screen = FakeScreen()
        val device = FakeDevice()
        screen.nodeList = listOf(titleNode("오늘의 뉴스"))
        device.onOpenUri = { screen.nodeList = listOf(n(text = "History"), n(text = "관계없는 영상")) }
        val agent = FakeAgent(result = false)
        val outcome = RestoreVideo(screen, device, { "오늘의 뉴스" }, agent).run(mapOf("title" to "트로트 메들리"))
        assertEquals(RecipeOutcome.Failed(FailReason.NOT_REACHED), outcome)
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
        assertEquals(RecipeOutcome.Failed(FailReason.NOT_REACHED), RestoreVideo(screen, device, { "오늘의 뉴스" }, FakeAgent()).run(mapOf("title" to "트로트 메들리")))
        assertTrue(screen.taps.isEmpty())
        assertEquals(0, screen.backs)
    }

    @Test
    fun `이미 원래 영상을 보고 있으면 아무것도 누르지 않고 조작 없는 성공`() = runTest {
        val screen = FakeScreen()
        assertEquals(RecipeOutcome.Verified(acted = false), RestoreVideo(screen, FakeDevice(), { "트로트 메들리" }, FakeAgent()).run(mapOf("title" to "트로트 메들리")))
        assertTrue(screen.clicks.isEmpty() && screen.taps.isEmpty())
    }

    @Test
    fun `돌아갈 영상 제목이 없으면 아무것도 누르지 않고 전제 조건 실패`() = runTest {
        val screen = FakeScreen()
        val device = FakeDevice()
        assertEquals(RecipeOutcome.Failed(FailReason.PRECONDITION), RestoreVideo(screen, device, { "오늘의 뉴스" }, FakeAgent()).run(mapOf("title" to "")))
        assertTrue(screen.clicks.isEmpty() && screen.taps.isEmpty() && device.opened.isEmpty())
    }

    @Test
    fun `조작 뒤 영상 제목을 읽지 못하면 실패가 아니라 검증 불가`() = runTest {
        val screen = FakeScreen()
        val prev = n(desc = "이전 동영상")
        screen.nodeList = listOf(prev)
        val outcome = RestoreVideo(screen, FakeDevice(), { null }, FakeAgent(result = false), verifyTimeoutMs = 300).run(mapOf("title" to "트로트 메들리"))
        assertTrue(outcome is RecipeOutcome.Unverifiable)
        assertEquals(listOf(prev), screen.clicks)
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
        assertEquals(RecipeOutcome.Verified(), SetVolume(device, store).run(emptyMap()))
        assertEquals(6, device.vol)
    }

    @Test
    fun `S4 이미 그 볼륨이면 고쳤다고 하지 않고 다른 원인을 안내한다`() = runTest {
        val store = BaselineStore(Clock { 0 }, emptySet())
        val device = FakeDevice().apply { vol = 7; max = 15 }
        assertEquals(RecipeOutcome.Guided(SOUND_IS_ON), SetVolume(device, store).run(emptyMap()))
    }

    @Test
    fun `S4 기록이 전혀 없으면 최대의 절반으로 켠다`() = runTest {
        val store = BaselineStore(Clock { 0 }, emptySet())
        val device = FakeDevice().apply { vol = 0; max = 15 }
        assertEquals(RecipeOutcome.Verified(), SetVolume(device, store).run(emptyMap()))
        assertEquals(7, device.vol)
    }

    @Test
    fun `S3 직전 앱을 다시 연다`() = runTest {
        val store = BaselineStore(Clock { 0 }, emptySet())
        val device = FakeDevice().apply { fg = "launcher" }
        assertEquals(RecipeOutcome.Verified(), OpenPreviousApp(device, store).run(mapOf("pkg" to yt)))
        assertEquals(listOf(yt), device.launched)
    }

    @Test
    fun `이름으로 열 때는 기기에 있는 패키지를 앞에서부터 찾아 연다`() = runTest {
        val device = FakeDevice().apply { fg = "launcher" }
        val photosOnly = object : DeviceActions by device {
            override fun launchApp(pkg: String) = pkg == "com.google.android.apps.photos" && device.launchApp(pkg)
        }
        val outcome = OpenPreviousApp(photosOnly, BaselineStore(Clock { 0 }, emptySet())).run(mapOf("app" to "gallery", "pkg" to yt))
        assertEquals(RecipeOutcome.Verified(), outcome)
        assertEquals("pkg보다 app을 따른다", listOf("com.google.android.apps.photos"), device.launched)
    }

    @Test
    fun `이름으로 연 앱을 확인하지 못해도 다음 후보 앱까지 열지 않는다`() = runTest {
        val device = FakeDevice()
        val blind = object : DeviceActions by device {
            override fun foregroundPkg(): String? = null
        }
        val outcome = OpenPreviousApp(blind, BaselineStore(Clock { 0 }, emptySet())).run(mapOf("app" to "gallery"))
        assertTrue(outcome is RecipeOutcome.Unverifiable)
        assertEquals(listOf("com.sec.android.gallery3d"), device.launched)
    }

    @Test
    fun `허용 목록에 없는 앱 이름이면 아무것도 열지 않는다`() = runTest {
        val device = FakeDevice()
        assertEquals(RecipeOutcome.Failed(FailReason.PRECONDITION), OpenPreviousApp(device, BaselineStore(Clock { 0 }, emptySet())).run(mapOf("app" to "bank")))
        assertTrue(device.launched.isEmpty())
    }

    @Test
    fun `S3 열 앱도 정상 상태도 없으면 전제 조건 실패`() = runTest {
        val device = FakeDevice()
        assertEquals(RecipeOutcome.Failed(FailReason.PRECONDITION), OpenPreviousApp(device, BaselineStore(Clock { 0 }, emptySet())).run(emptyMap()))
        assertTrue(device.launched.isEmpty())
    }

    private val wifiToggle = { n(desc = "Wi‑Fi", viewId = "com.android.systemui:id/wifi_toggle") }
    private val airplaneButton = { n(text = "Turn off airplane mode", viewId = "com.android.systemui:id/apm_button") }

    @Test
    fun `S8 와이파이가 꺼져 있으면 패널에서 켜고 연결되면 패널을 닫는다`() = runTest {
        val device = FakeDevice().apply { online = false; wifiOn = false }
        val toggle = wifiToggle()
        val screen = FakeScreen()
        device.onOpenPanel = { screen.nodeList = listOf(toggle); screen.pkg = "com.android.systemui" }
        screen.onClick = { if (it === toggle) { device.wifiOn = true; device.online = true } }
        assertEquals(RecipeOutcome.Verified(), OpenInternetPanel(screen, device).run(emptyMap()))
        assertEquals(listOf(toggle), screen.clicks)
        assertEquals(1, screen.backs)
    }

    @Test
    fun `S8 연결을 기다리는 사이 사용자가 패널을 닫았으면 뒤로 가기를 누르지 않는다`() = runTest {
        val device = FakeDevice().apply { online = false; wifiOn = false }
        val toggle = wifiToggle()
        val screen = FakeScreen()
        device.onOpenPanel = { screen.nodeList = listOf(toggle); screen.pkg = "com.android.systemui" }
        screen.onClick = { device.wifiOn = true; device.online = true; screen.pkg = "com.google.android.youtube" }
        assertEquals(RecipeOutcome.Verified(), OpenInternetPanel(screen, device).run(emptyMap()))
        assertEquals(0, screen.backs)
    }

    @Test
    fun `S8 비행기 모드를 끈 뒤 와이파이가 늦게 켜지면 기다렸다가 스위치는 누르지 않는다`() = runTest {
        val device = FakeDevice().apply { online = false; wifiOn = false }
        val apm = airplaneButton()
        val screen = FakeScreen()
        device.onOpenPanel = { screen.nodeList = listOf(apm, wifiToggle()) }
        screen.onClick = {
            if (it === apm) launch { delay(1_800); device.wifiOn = true; delay(500); device.online = true }
        }
        assertEquals(RecipeOutcome.Verified(), OpenInternetPanel(screen, device).run(emptyMap()))
        assertEquals("켜지는 중인 와이파이를 눌러 끄지 않는다", listOf(apm), screen.clicks)
    }

    @Test
    fun `S8 비행기 모드면 먼저 끄고, 와이파이가 스스로 켜지면 스위치는 누르지 않는다`() = runTest {
        val device = FakeDevice().apply { online = false; wifiOn = false }
        val apm = airplaneButton()
        val screen = FakeScreen()
        device.onOpenPanel = { screen.nodeList = listOf(apm, wifiToggle()) }
        screen.onClick = { if (it === apm) { device.wifiOn = true; device.online = true } }
        assertEquals(RecipeOutcome.Verified(), OpenInternetPanel(screen, device).run(emptyMap()))
        assertEquals("켜진 와이파이를 다시 눌러 끄지 않는다", listOf(apm), screen.clicks)
    }

    @Test
    fun `S8 와이파이는 켜져 있는데 연결이 안 되면 스위치를 누르라고 하지 않고 공유기를 안내한다`() = runTest {
        val device = FakeDevice().apply { online = false; wifiOn = true }
        val screen = FakeScreen()
        device.onOpenPanel = { screen.nodeList = listOf(wifiToggle()); screen.pkg = "com.android.systemui" }
        assertEquals(RecipeOutcome.Guided("와이파이는 켜져 있는데 인터넷이 안 돼요. 공유기를 확인해 주세요."), OpenInternetPanel(screen, device).run(emptyMap()))
        assertTrue(screen.clicks.isEmpty())
        assertEquals("열어 둔 패널은 닫는다", 1, screen.backs)
    }

    @Test
    fun `S8 와이파이가 꺼져 있는데 스위치를 못 찾으면 직접 켜도록 안내한다`() = runTest {
        val device = FakeDevice().apply { online = false; wifiOn = false }
        assertEquals(RecipeOutcome.Guided("화면에서 와이파이를 눌러 켜 주세요"), OpenInternetPanel(FakeScreen(), device).run(emptyMap()))
    }

    @Test
    fun `S8 켜도 10초 안에 연결되지 않으면 실패`() = runTest {
        val device = FakeDevice().apply { online = false; wifiOn = false }
        val screen = FakeScreen()
        device.onOpenPanel = { screen.nodeList = listOf(wifiToggle()) }
        assertEquals(RecipeOutcome.Failed(FailReason.NOT_REACHED), OpenInternetPanel(screen, device).run(emptyMap()))
    }

    @Test
    fun `S8 이미 연결돼 있으면 패널을 열지 않는다`() = runTest {
        val device = FakeDevice().apply { online = true }
        assertTrue(OpenInternetPanel(FakeScreen(), device).run(emptyMap()) is RecipeOutcome.Guided)
        assertTrue(device.panels.isEmpty())
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
        screen.onClick = { screen.nodeList = listOf(n(text = "트로트 메들리", clickable = false)) }
        assertEquals(RecipeOutcome.Verified(), SkipAd(delayed, voice).run(emptyMap()))
        assertEquals(listOf(skip), screen.clicks)
        assertEquals(listOf("잠시만요, 곧 넘길 수 있어요"), voice.spoken)
    }

    @Test
    fun `S11 누른 뒤 화면을 읽지 못하면 버튼이 사라졌다고 보지 않고 검증 불가`() = runTest {
        val skip = n(text = "광고 건너뛰기")
        val screen = FakeScreen().apply { nodeList = listOf(skip); onClick = { nodeList = emptyList() } }
        assertTrue(SkipAd(screen, FakeVoice()).run(emptyMap()) is RecipeOutcome.Unverifiable)
    }

    @Test
    fun `S11 눌렀는데 건너뛰기 버튼이 그대로면 실패`() = runTest {
        val skip = n(text = "광고 건너뛰기")
        val screen = FakeScreen().apply { nodeList = listOf(skip) }
        assertEquals(RecipeOutcome.Failed(FailReason.NOT_REACHED), SkipAd(screen, FakeVoice()).run(emptyMap()))
    }

    @Test
    fun `S3 앱을 연 뒤 지금 앱을 읽지 못하면 검증 불가`() = runTest {
        val device = FakeDevice()
        val blind = object : DeviceActions by device {
            override fun foregroundPkg(): String? = null
        }
        assertTrue(OpenPreviousApp(blind, BaselineStore(Clock { 0 }, emptySet())).run(mapOf("pkg" to yt)) is RecipeOutcome.Unverifiable)
        assertEquals(listOf(yt), device.launched)
    }

    @Test
    fun `S11 건너뛸 수 없는 광고면 안내만 하고 끝낸다`() = runTest {
        val screen = FakeScreen().apply { nodeList = listOf(n(text = "광고 · 0:15", clickable = false)) }
        val voice = FakeVoice()
        assertEquals(RecipeOutcome.Guided("광고가 끝나면 이어서 나와요"), SkipAd(screen, voice).run(emptyMap()))
        assertTrue(screen.clicks.isEmpty())
        assertEquals("마지막 안내는 컨트롤러가 말한다", listOf("잠시만요, 곧 넘길 수 있어요"), voice.spoken)
    }
}
