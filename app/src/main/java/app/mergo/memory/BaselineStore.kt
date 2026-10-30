package app.mergo.memory

import app.mergo.model.Baseline
import app.mergo.model.Clock
import app.mergo.model.ScreenEvent

/**
 * 사용자가 의도적으로 만든 마지막 상태(정상 상태)를 관리한다.
 *
 * - 사용자 동작 직후(2초 안) 바뀐 값이 유지되면 확정한다. 앱·볼륨·연결은 5초, 영상 제목은 30초 시청이 기준이다.
 * - 자동으로 생긴 변화(자동재생 등)는 확정하지 않는다.
 * - 직전 정상 상태 3개를 스택으로 보관한다.
 * - 제안을 거절하면 [acceptCurrent]로 지금 상태를 바로 정상 상태로 인정한다.
 */
class BaselineStore(
    private val clock: Clock,
    private val ignoredPkgs: Set<String>,
    private val userActionWindowMs: Long = 2_000L,
    // 영상은 누른 뒤 제목이 뜨기까지 로딩이 걸리므로 조금 더 넉넉하게 본다.
    private val titleUserWindowMs: Long = 5_000L,
    private val holdMs: Long = 5_000L,
    private val videoHoldMs: Long = 30_000L,
    private val maxStack: Int = 3,
    /**
     * true면 영상 제목은 '카운트다운 없이 바뀌었나'로 사용자 선택을 판단한다(YouTube 탭은 클릭 이벤트가 오지 않음).
     * false면 터치 이벤트 기준(감지기의 requireCountdown=false 대체 모드와 짝).
     */
    private val titleByCountdown: Boolean = true,
    private val countdownLookbackMs: Long = 15_000L,
) {
    private class Live<T>(var value: T, var since: Long, var byUser: Boolean)

    private val stack = ArrayDeque<Baseline>()
    private var lastUserActionTs = Long.MIN_VALUE / 2
    private var lastCountdownTs = Long.MIN_VALUE / 2

    private var pkg: Live<String?> = Live(null, 0, false)
    private var title: Live<String?> = Live(null, 0, false)
    private var volume: Live<Int?> = Live(null, 0, false)
    private var online: Live<Boolean?> = Live(null, 0, false)

    /** 볼륨 0을 정상 상태로 확정했더라도 복원 목표로 쓸 수 있게, 마지막으로 소리가 났던 볼륨을 따로 기억한다. */
    var lastAudibleVolume: Int? = null
        private set

    val current: Baseline? get() = stack.lastOrNull()
    val history: List<Baseline> get() = stack.toList()

    @Synchronized
    fun onScreen(e: ScreenEvent) {
        if (e.userAction) lastUserActionTs = e.ts
        if (app.mergo.model.Marker.AUTOPLAY_COUNTDOWN in e.markers) lastCountdownTs = e.ts
        if (e.pkg in ignoredPkgs) return
        update(pkg, e.pkg, e.ts)
        if (e.title != null) {
            if (titleByCountdown) {
                if (title.value != e.title) {
                    title.value = e.title
                    title.since = e.ts
                    title.byUser = e.ts - lastCountdownTs > countdownLookbackMs
                }
            } else {
                update(title, e.title, e.ts, titleUserWindowMs)
            }
        }
    }

    @Synchronized
    fun onDevice(volumeNow: Int, onlineNow: Boolean, ts: Long = clock.now(), userAction: Boolean = false) {
        if (userAction) lastUserActionTs = ts
        update(volume, volumeNow, ts)
        update(online, onlineNow, ts)
        // 첫 관측값은 시작 상태로 인정한다. 홈 화면만 보고 있어 앱이 아직 없어도 볼륨·연결은 기억한다.
        if (stack.isEmpty()) commit(ts)
    }

    /** 주기적으로 불러 확정 조건을 확인한다. 확정하면 true. */
    @Synchronized
    fun tick(now: Long = clock.now()): Boolean {
        val cur = current
        val ready = { l: Live<*>, hold: Long, committed: Any? ->
            l.value != null && l.byUser && now - l.since >= hold && l.value != committed
        }
        val changed = cur == null && volume.value != null ||
            cur != null && (
                ready(pkg, holdMs, cur.pkg) ||
                    ready(title, videoHoldMs, cur.mediaTitle) ||
                    ready(volume, holdMs, cur.volume) ||
                    ready(online, holdMs, cur.online)
                )
        if (!changed) return false
        commit(now)
        return true
    }

    /** 거절 등으로 지금 상태를 정상 상태로 인정한다. */
    @Synchronized
    fun acceptCurrent(now: Long = clock.now()) {
        commit(now, force = true)
    }

    @Synchronized
    fun liveTitle(): String? = title.value

    @Synchronized
    fun livePkg(): String? = pkg.value

    private fun <T> update(l: Live<T>, v: T, ts: Long, window: Long = userActionWindowMs) {
        if (l.value == v) return
        l.value = v
        l.since = ts
        l.byUser = ts - lastUserActionTs <= window
    }

    private fun commit(now: Long, force: Boolean = false) {
        val cur = current
        // 사용자가 만든 값만 새 정상 상태에 반영하고, 나머지는 직전 정상 상태 값을 유지한다.
        fun <T> pick(l: Live<T>, hold: Long, old: T?): T? =
            if (cur == null || force || (l.byUser && now - l.since >= hold)) l.value ?: old else old
        val next = Baseline(
            pkg = pick(pkg, holdMs, cur?.pkg) ?: "",  // 빈 값 = 아직 앱을 모름
            mediaTitle = pick(title, videoHoldMs, cur?.mediaTitle),
            volume = pick(volume, holdMs, cur?.volume) ?: return,
            online = pick(online, holdMs, cur?.online) ?: true,
            updatedAt = now,
        )
        if (next.volume > 0) lastAudibleVolume = next.volume
        if (next == cur?.copy(updatedAt = now)) return
        stack.addLast(next)
        while (stack.size > maxStack) stack.removeFirst()
    }
}
