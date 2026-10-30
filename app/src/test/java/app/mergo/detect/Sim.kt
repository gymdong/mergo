package app.mergo.detect

import app.mergo.memory.BaselineStore
import app.mergo.model.Clock
import app.mergo.model.EventKind
import app.mergo.model.Marker
import app.mergo.model.ScreenEvent

const val YT = "com.google.android.youtube"
const val LAUNCHER = "com.google.android.apps.nexuslauncher"
const val SETTINGS = "com.android.settings"

/** 감지기와 정상 상태 저장소에 이벤트를 시간 순서대로 넣고, 그 사이를 1초 단위 tick으로 채우는 재생기. */
class Sim(config: DetectorConfig = DetectorConfig(launcherPkgs = setOf(LAUNCHER))) {
    var now = 0L
        private set
    private val clock = Clock { now }
    val cooldown = Cooldown()
    val baseline = BaselineStore(clock, ignoredPkgs = config.launcherPkgs + config.ignoredPkgs, titleByCountdown = config.requireCountdown)
    val detector = StuckDetector(clock, config, cooldown) { baseline.current?.mediaTitle }
    val suggestions = mutableListOf<Suggestion>()

    init {
        baseline.onDevice(volumeNow = 8, onlineNow = true, ts = 0)
    }

    fun advanceTo(ts: Long) {
        require(ts >= now) { "시간은 앞으로만 간다: $now -> $ts" }
        var t = (now / 1000 + 1) * 1000
        while (t <= ts) {
            now = t
            baseline.tick(t)
            suggestions += detector.tick(t)
            t += 1000
        }
        now = ts
    }

    fun event(
        ts: Long,
        kind: EventKind,
        pkg: String = YT,
        title: String? = null,
        user: Boolean = kind in setOf(EventKind.CLICK, EventKind.SCROLL, EventKind.TOUCH, EventKind.KEY, EventKind.BACK),
        markers: Set<Marker> = emptySet(),
    ) {
        advanceTo(ts)
        val e = ScreenEvent(ts, pkg, title, emptyList(), user, kind, markers)
        baseline.onScreen(e)
        suggestions += detector.onEvent(e)
    }

    /** 사용자가 영상을 직접 골라 [watchMs] 동안 본다. 제목은 누른 뒤 1초 뒤에 뜬다. */
    fun userPlays(ts: Long, title: String, watchMs: Long = 35_000) {
        event(ts, EventKind.CLICK)
        event(ts + 1000, EventKind.CONTENT_CHANGED, title = title, markers = setOf(Marker.VIDEO_PLAYER))
        playing(ts + 1000, ts + 1000 + watchMs, title)
    }

    /** 재생 중에는 화면 내용 변화가 주기적으로 온다. */
    fun playing(from: Long, to: Long, title: String, step: Long = 5_000) {
        var t = from + step
        while (t <= to) {
            event(t, EventKind.CONTENT_CHANGED, title = title, markers = setOf(Marker.VIDEO_PLAYER))
            t += step
        }
    }

    fun rules() = suggestions.map { it.rule }
}
