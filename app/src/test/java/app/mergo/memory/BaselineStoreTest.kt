package app.mergo.memory

import app.mergo.model.Clock
import app.mergo.model.EventKind
import app.mergo.model.Marker
import app.mergo.model.ScreenEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// AC-D7
class BaselineStoreTest {
    private var now = 0L
    private val store = BaselineStore(Clock { now }, ignoredPkgs = setOf("launcher"))
    private val yt = "com.google.android.youtube"

    private fun ev(ts: Long, kind: EventKind, title: String? = null, pkg: String = yt, user: Boolean = kind == EventKind.CLICK) {
        now = ts
        store.onScreen(ScreenEvent(ts, pkg, title, emptyList(), user, kind))
    }

    private fun tickUntil(ts: Long) {
        while (now < ts) {
            now += 1000
            store.tick(now)
        }
    }

    private fun start() {
        ev(0, EventKind.WINDOW_CHANGED)
        store.onDevice(volumeNow = 8, onlineNow = true, ts = 0)
    }

    @Test
    fun `직접 고른 영상은 30초 미만이면 확정하지 않고 30초가 지나면 확정한다`() {
        start()
        ev(1_000, EventKind.CLICK)
        ev(2_000, EventKind.CONTENT_CHANGED, "영상 가")
        tickUntil(25_000)
        assertNull(store.current?.mediaTitle)
        tickUntil(33_000)
        assertEquals("영상 가", store.current?.mediaTitle)
    }

    @Test
    fun `자동으로 바뀐 영상은 오래 봐도 확정하지 않는다`() {
        start()
        ev(1_000, EventKind.CLICK)
        ev(2_000, EventKind.CONTENT_CHANGED, "영상 가")
        tickUntil(35_000)
        now = 35_000
        store.onScreen(ScreenEvent(35_000, yt, "영상 가", emptyList(), false, EventKind.CONTENT_CHANGED, setOf(Marker.AUTOPLAY_COUNTDOWN)))
        ev(40_000, EventKind.CONTENT_CHANGED, "자동재생 영상")
        tickUntil(200_000)
        assertEquals("영상 가", store.current?.mediaTitle)
    }

    @Test
    fun `탭 이벤트가 오지 않아도 카운트다운 없이 바뀐 영상은 사용자가 고른 것으로 보고 30초 뒤 확정한다`() {
        start()
        ev(2_000, EventKind.CONTENT_CHANGED, "영상 가")
        tickUntil(33_000)
        assertEquals("영상 가", store.current?.mediaTitle)
    }

    @Test
    fun `거절하면 지금 상태를 바로 정상 상태로 인정한다`() {
        start()
        ev(1_000, EventKind.CONTENT_CHANGED, "자동재생 영상")
        store.acceptCurrent(2_000)
        assertEquals("자동재생 영상", store.current?.mediaTitle)
    }

    @Test
    fun `스택은 최대 3개만 보관한다`() {
        start()
        var t = 1_000L
        for (name in listOf("가", "나", "다", "라")) {
            ev(t, EventKind.CLICK)
            ev(t + 1_000, EventKind.CONTENT_CHANGED, name)
            tickUntil(t + 35_000)
            t += 40_000
        }
        assertEquals(3, store.history.size)
        assertEquals(listOf("나", "다", "라"), store.history.map { it.mediaTitle })
    }

    @Test
    fun `볼륨을 0으로 확정해도 마지막으로 소리가 나던 볼륨을 기억한다`() {
        start()
        store.onDevice(volumeNow = 0, onlineNow = true, ts = 10_000, userAction = true)
        tickUntil(20_000)
        assertEquals(0, store.current?.volume)
        assertEquals(8, store.lastAudibleVolume)
    }

    @Test
    fun `홈 화면 같은 무시 패키지는 정상 상태 앱이 되지 않는다`() {
        start()
        ev(5_000, EventKind.CLICK, pkg = "launcher")
        ev(5_100, EventKind.WINDOW_CHANGED, pkg = "launcher")
        tickUntil(20_000)
        assertEquals(yt, store.current?.pkg)
    }
}
