package app.mergo.memory

import app.mergo.model.Clock
import app.mergo.model.EventKind
import app.mergo.model.Marker
import app.mergo.model.ScreenEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneOffset

class TimelineBufferTest {
    private var now = 0L
    private val buf = TimelineBuffer(Clock { now }, zone = ZoneOffset.UTC)

    private fun e(ts: Long, title: String?, kind: EventKind = EventKind.CONTENT_CHANGED, user: Boolean = false) =
        ScreenEvent(ts, "yt", title, listOf("a"), user, kind, setOf(Marker.VIDEO_PLAYER))

    @Test
    fun `0점5초 안에 같은 화면 변화는 하나로 묶는다`() {
        buf.add(e(0, "가"))
        assertFalse(buf.add(e(300, "가")))
        buf.add(e(900, "가"))
        assertEquals(2, buf.snapshot().size)
    }

    @Test
    fun `10분이 지난 기록은 버린다`() {
        buf.add(e(0, "가"))
        now = 11 * 60_000L
        buf.add(e(now, "나"))
        assertEquals(listOf("나"), buf.snapshot().map { it.title })
    }

    @Test
    fun `요약은 제목이 바뀐 지점만 남기고 자동 변화를 표시한다`() {
        buf.add(e(0, "가", EventKind.CLICK, user = true))
        buf.add(e(1_000, "가"))
        buf.add(e(180_000, "나"))
        assertEquals(listOf("00:00 yt · 가", "00:03 yt · 나 (자동)"), buf.summaryLines())
    }

    @Test
    fun `JSON으로 내보내고 다시 읽을 수 있다`() {
        buf.add(e(0, "가"))
        buf.add(e(1_000, "나"))
        assertEquals(buf.snapshot(), TimelineBuffer.fromJson(buf.toJson()))
    }
}
