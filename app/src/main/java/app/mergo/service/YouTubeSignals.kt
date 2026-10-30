package app.mergo.service

import app.mergo.model.Marker
import app.mergo.model.NodeSnapshot

/**
 * YouTube 화면 노드에서 영상 제목과 감지 신호를 뽑는다.
 *
 * 패턴은 사전 검증(`adb shell uiautomator dump`) 결과로 보정한다. 노드에서 제목을 못 얻으면
 * [MediaTitleSource](MediaSession)의 제목을 쓴다.
 */
object YouTubeSignals {
    const val PKG = "com.google.android.youtube"

    /** 재생 화면 제목 후보 viewId. */
    val titleViewIds = listOf(
        "$PKG:id/title",
        "$PKG:id/video_title",
        "$PKG:id/player_video_title_view",
    )
    /**
     * 재생 화면 플레이어 viewId (우선순위 순). 에뮬레이터 확인: `watch_player`(desc "Video player").
     * `slim_status_bar_player_container`처럼 화면 전체를 덮는 컨테이너가 있어 'player'가 들어간 아무 id나 쓰면 안 된다.
     */
    val playerViewIds = listOf("$PKG:id/watch_player", "$PKG:id/player_view")

    val countdown = Regex("다음 동영상|다음 영상|Up next|Up Next|자동재생")
    /** 카운트다운 화면에는 자동재생을 멈추는 '취소' 버튼이 함께 뜬다. 플레이어의 '다음 동영상' 버튼과 구분하는 근거다. */
    val countdownCancel = Regex("^(취소|Cancel)$")
    val previousVideo = Regex("^(이전 동영상|이전 영상|Previous video)$")
    val skipAd = Regex("건너뛰기|Skip")
    // 추천 목록의 'Sponsored' 카드와 구분하려고, 광고·건너뛰기는 플레이어 영역 안의 노드만 본다.
    val adShowing = Regex("^(광고|Ad)( ·.*)?$|^광고 \\d|^Ad \\d")
    val playPause = Regex("^(재생|일시중지|일시 중지|Play video|Pause video|Play|Pause)$")

    /** 위험한 버튼에 스킵 패턴이 우연히 걸리지 않게 막는다. */
    private val notSkip = Regex("설치|구매|결제|다운로드")

    /**
     * 노드에서 영상 제목을 찾는다. 2026-10 에뮬레이터 확인 결과 제목 노드에는 viewId가 없고,
     * 플레이어(`watch_player`) 바로 아래 첫 글자 노드로 나온다. MediaSession을 못 쓸 때의 대안이다.
     */
    fun title(nodes: List<NodeSnapshot>): String? {
        nodes.firstOrNull { it.viewId in titleViewIds && !it.text.isNullOrBlank() }?.text?.trim()?.let { return it }
        val player = playerNode(nodes) ?: return null
        return nodes.filter { n ->
            !n.text.isNullOrBlank() && n.text.length >= 4 &&
                n.bounds.t >= player.bounds.b && n.bounds.t - player.bounds.b <= TITLE_GAP_PX
        }.minByOrNull { it.bounds.t }?.text?.trim()
    }

    private const val TITLE_GAP_PX = 160

    fun markers(nodes: List<NodeSnapshot>): Set<Marker> {
        val out = mutableSetOf<Marker>()
        val player = playerNode(nodes)
        var countdownText = false
        var cancel = false
        for (n in nodes) {
            val label = listOfNotNull(n.text, n.desc).joinToString(" ")
            if (label.isBlank() && n.viewId == null) continue
            if (n.text?.let(countdown::containsMatchIn) == true) countdownText = true
            if (listOfNotNull(n.text, n.desc).any { countdownCancel.matches(it.trim()) }) cancel = true
            if (isSkipButton(n, player)) out += Marker.AD_SKIP_VISIBLE
            if (inPlayer(n, player) &&
                (n.text?.let { adShowing.containsMatchIn(it.trim()) } == true || n.desc?.let { adShowing.containsMatchIn(it.trim()) } == true)
            ) {
                out += Marker.AD_SHOWING
            }
            if (n.viewId in playerViewIds || n.desc?.let { playPause.matches(it.trim()) } == true) {
                out += Marker.VIDEO_PLAYER
            }
        }
        if (countdownText && cancel) out += Marker.AUTOPLAY_COUNTDOWN
        return out
    }

    /** 건너뛰기 버튼: 짧은 글자(영상 제목에 'Skip'이 들어간 추천 카드 제외), 플레이어를 알면 그 안에 있어야 한다. */
    fun isSkipButton(n: NodeSnapshot, player: NodeSnapshot? = null): Boolean {
        val label = listOfNotNull(n.text, n.desc).joinToString(" ")
        return n.clickable && n.visible && label.length <= 30 && inPlayer(n, player) &&
            skipAd.containsMatchIn(label) && !notSkip.containsMatchIn(label)
    }

    /** 플레이어를 모르면(테스트·전체화면 등) 제한하지 않는다. */
    private fun inPlayer(n: NodeSnapshot, player: NodeSnapshot?): Boolean {
        if (player == null) return true
        val p = player.bounds
        return n.bounds.centerX in p.l..p.r && n.bounds.centerY in p.t..p.b
    }

    /** 에뮬레이터 확인: viewId `player_control_previous_button`, desc "Previous video"(영어 UI). */
    fun previousButton(nodes: List<NodeSnapshot>): NodeSnapshot? =
        nodes.firstOrNull { n ->
            n.visible && (
                n.viewId == "$PKG:id/player_control_previous_button" ||
                    n.desc?.trim()?.let(previousVideo::matches) == true || n.text?.trim()?.let(previousVideo::matches) == true
                )
        }

    /** 플레이어 영역. 컨트롤을 띄우려고 탭할 위치를 정한다. */
    fun playerNode(nodes: List<NodeSnapshot>): NodeSnapshot? =
        playerViewIds.firstNotNullOfOrNull { id -> nodes.firstOrNull { it.visible && it.viewId == id } }

    /** 시청 기록 화면인지. 실패 후 정리할 때 이 화면일 때만 뒤로 가기를 누른다. */
    val historyHeading = Regex("^(History|시청 기록|기록)$")

    /** 제목 비교용 정규화. 공백·대소문자 차이를 무시한다. */
    fun sameTitle(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val na = normalize(a)
        val nb = normalize(b)
        return na == nb || (na.length >= 6 && nb.length >= 6 && (na.startsWith(nb) || nb.startsWith(na)))
    }

    fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
}
