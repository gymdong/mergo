package app.mergo.model

import kotlinx.serialization.Serializable

/** 접근성 이벤트를 텍스트로만 줄인 기록 한 줄. 스크린샷은 남기지 않는다. */
@Serializable
data class ScreenEvent(
    val ts: Long,
    val pkg: String,
    val title: String?,              // 화면·영상 제목
    val texts: List<String>,         // 주요 글자 최대 10개
    val userAction: Boolean,         // 이 이벤트 자체가 사용자 조작인가 (클릭·스크롤·터치·키)
    val kind: EventKind,
    val markers: Set<Marker> = emptySet(),
)

@Serializable
enum class EventKind { WINDOW_CHANGED, CONTENT_CHANGED, CLICK, SCROLL, TOUCH, KEY, BACK }

/** 화면에서 찾은 의미 있는 신호. 노드 트리 전체를 넘기지 않고 감지에 필요한 것만 표시한다. */
@Serializable
enum class Marker {
    AUTOPLAY_COUNTDOWN,  // '다음 동영상' 자동재생 카운트다운
    AD_SKIP_VISIBLE,     // 광고 건너뛰기 버튼
    AD_SHOWING,          // 광고 재생 중 표시
    VIDEO_PLAYER,        // 영상 재생 화면
}

/** 접근성 노드를 번호·글자·위치로 줄인 스냅샷. ScreenAgent와 레시피가 함께 쓴다. */
@Serializable
data class NodeSnapshot(
    val id: Int,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val clickable: Boolean,
    val bounds: Rect4,
    val visible: Boolean,
)

@Serializable
data class Rect4(val l: Int, val t: Int, val r: Int, val b: Int) {
    val centerX: Int get() = (l + r) / 2
    val centerY: Int get() = (t + b) / 2
    val width: Int get() = r - l
    val height: Int get() = b - t
}

/** 사용자가 의도적으로 만든 마지막 상태. 지금 상태와의 차이가 복구 대상이다. */
@Serializable
data class Baseline(
    val pkg: String,
    val mediaTitle: String?,
    val volume: Int,
    val online: Boolean,
    val updatedAt: Long,
)
