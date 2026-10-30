package app.mergo.service

import app.mergo.model.Marker
import app.mergo.model.NodeSnapshot
import app.mergo.model.Rect4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSignalsTest {
    private var nextId = 0
    private fun node(text: String? = null, desc: String? = null, viewId: String? = null, clickable: Boolean = true) =
        NodeSnapshot(nextId++, text, desc, viewId, clickable, Rect4(0, 0, 100, 50), visible = true)

    // AC-R2
    @Test
    fun `건너뛰기 노드는 여러 표기에 매칭되고 설치하기에는 매칭되지 않는다`() {
        for (label in listOf("건너뛰기", "광고 건너뛰기", "Skip", "Skip ad")) {
            assertTrue(label, YouTubeSignals.isSkipButton(node(text = label)))
        }
        assertTrue(YouTubeSignals.isSkipButton(node(desc = "광고 건너뛰기")))
        assertFalse(YouTubeSignals.isSkipButton(node(text = "설치하기")))
        assertFalse(YouTubeSignals.isSkipButton(node(text = "건너뛰고 설치하기")))
        assertFalse("클릭 못 하는 노드는 버튼이 아니다", YouTubeSignals.isSkipButton(node(text = "건너뛰기", clickable = false)))
    }

    @Test
    fun `에뮬레이터 실측 광고 화면 - 글자 없는 skip_ad_button을 찾고, 건너뛰기 전 카운트다운은 버튼이 아니다`() {
        val yt = YouTubeSignals.PKG
        val player = NodeSnapshot(1, null, "Video player", "$yt:id/watch_player", false, Rect4(0, 63, 1080, 671), true)
        val countdown = NodeSnapshot(2, "5", "5 seconds to skip ad", "$yt:id/countdown_text", true, Rect4(850, 471, 910, 555), true)
        val progress = NodeSnapshot(3, "Sponsored · 1:46", null, "$yt:id/ad_progress_text", true, Rect4(27, 560, 315, 655), true)
        val adPanel = NodeSnapshot(4, "Meet Grok Bot", null, null, true, Rect4(84, 700, 819, 780), true)
        val before = listOf(player, countdown, progress, adPanel)
        assertEquals(setOf(Marker.VIDEO_PLAYER, Marker.AD_SHOWING), YouTubeSignals.markers(before))
        assertEquals("광고 중에는 광고 패널 글자를 제목으로 읽지 않는다", null, YouTubeSignals.title(before))

        val button = NodeSnapshot(5, null, null, "$yt:id/skip_ad_button", true, Rect4(882, 450, 1080, 576), true)
        val container = NodeSnapshot(6, null, "Skip ad", "$yt:id/skip_ad_button_container", false, Rect4(882, 471, 1059, 555), true)
        val text = NodeSnapshot(7, "Skip", null, "$yt:id/skip_ad_button_text", false, Rect4(914, 491, 996, 534), true)
        val after = listOf(player, progress, button, container, text)
        assertTrue(Marker.AD_SKIP_VISIBLE in YouTubeSignals.markers(after))
        assertEquals(listOf(button), after.filter { YouTubeSignals.isSkipButton(it, player) })
    }

    @Test
    fun `조회수 줄은 제목으로 읽지 않는다`() {
        val yt = YouTubeSignals.PKG
        val nodes = listOf(
            NodeSnapshot(1, null, "Video player", "$yt:id/watch_player", false, Rect4(0, 63, 1080, 670), true),
            NodeSnapshot(2, "6.1B views", null, null, false, Rect4(0, 700, 400, 740), true),
        )
        assertEquals(null, YouTubeSignals.title(nodes))
    }

    @Test
    fun `화면 신호를 뽑는다`() {
        val nodes = listOf(
            node(text = "다음 동영상", clickable = false),
            node(text = "취소"),
            node(desc = "일시중지"),
            node(text = "광고 · 0:15", clickable = false),
            node(text = "광고 건너뛰기"),
        )
        assertEquals(
            setOf(Marker.AUTOPLAY_COUNTDOWN, Marker.VIDEO_PLAYER, Marker.AD_SHOWING, Marker.AD_SKIP_VISIBLE),
            YouTubeSignals.markers(nodes),
        )
    }

    @Test
    fun `플레이어의 다음 동영상 버튼만으로는 카운트다운이 아니다`() {
        val nodes = listOf(node(desc = "다음 동영상"), node(text = "다음 동영상", clickable = false), node(desc = "일시중지"))
        assertEquals(setOf(Marker.VIDEO_PLAYER), YouTubeSignals.markers(nodes))
    }

    @Test
    fun `에뮬레이터 실제 화면 - 제목은 플레이어 바로 아래 글자이고 추천 목록 Sponsored는 광고가 아니다`() {
        val nodes = listOf(
            NodeSnapshot(1, null, "Video player", "${YouTubeSignals.PKG}:id/watch_player", false, Rect4(0, 63, 1080, 670), true),
            NodeSnapshot(2, "World’s Largest Economies | GDP History", null, null, false, Rect4(0, 702, 1080, 812), true),
            NodeSnapshot(3, "563K views. 3 months ago.", null, null, false, Rect4(32, 828, 553, 871), true),
            NodeSnapshot(4, "Sponsored", "Sponsored", null, false, Rect4(159, 2199, 314, 2242), true),
            NodeSnapshot(5, null, "How to Skip Work - play video", null, true, Rect4(0, 1444, 1080, 2306), true),
        )
        assertEquals("World’s Largest Economies | GDP History", YouTubeSignals.title(nodes))
        assertEquals(setOf(Marker.VIDEO_PLAYER), YouTubeSignals.markers(nodes))
    }

    @Test
    fun `일반 화면에서는 신호가 없다`() {
        val nodes = listOf(node(text = "홈"), node(text = "구독"), node(text = "광고 없는 영상 추천"))
        assertEquals(emptySet<Marker>(), YouTubeSignals.markers(nodes))
    }

    @Test
    fun `이전 동영상 버튼과 제목을 찾는다`() {
        val nodes = listOf(
            node(text = "트로트 메들리", viewId = "${YouTubeSignals.PKG}:id/title", clickable = false),
            node(desc = "이전 동영상"),
            node(desc = "다음 동영상"),
        )
        assertEquals("트로트 메들리", YouTubeSignals.title(nodes))
        assertEquals("이전 동영상", YouTubeSignals.previousButton(nodes)?.desc)
    }

    @Test
    fun `제목 비교는 공백과 대소문자를 무시하고 잘린 제목도 같게 본다`() {
        assertTrue(YouTubeSignals.sameTitle("트로트  메들리 2026", "트로트 메들리 2026"))
        assertTrue(YouTubeSignals.sameTitle("Trot Medley Best", "trot medley best"))
        assertTrue(YouTubeSignals.sameTitle("트로트 메들리 모음 1시간", "트로트 메들리 모음"))
        assertFalse(YouTubeSignals.sameTitle("트로트", "뉴스"))
        assertFalse(YouTubeSignals.sameTitle(null, "뉴스"))
    }
}
