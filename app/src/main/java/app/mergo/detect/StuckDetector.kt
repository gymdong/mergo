package app.mergo.detect

import app.mergo.model.Clock
import app.mergo.model.EventKind
import app.mergo.model.Marker
import app.mergo.model.RecipeIds
import app.mergo.model.ScreenEvent

/**
 * 막힘 감지 규칙 R1~R4. 안드로이드 타입에 의존하지 않아 JVM에서 이벤트 시퀀스를 재생해 검증한다.
 *
 * 핵심은 '사용자가 직접 한 동작인가'를 가르는 것이다. 자동화(레시피 실행) 중 생긴 이벤트는
 * 호출하는 쪽에서 userAction=false로 바꿔 넣는다.
 *
 * @param baselineTitle 확정된 정상 상태의 영상 제목(30초 이상 본 영상)을 돌려준다.
 */
class StuckDetector(
    private val clock: Clock,
    private val config: DetectorConfig,
    private val cooldown: Cooldown,
    private val baselineTitle: () -> String?,
) {
    private var lastUserActionTs = Long.MIN_VALUE / 2
    private var lastCountdownTs = Long.MIN_VALUE / 2
    private val backPresses = ArrayDeque<Long>()

    private var ytTitle: String? = null
    private var lastTitleChangeTs = Long.MIN_VALUE / 2
    private var lastVideoTs = Long.MIN_VALUE / 2
    private var foregroundPkg: String? = null

    private data class PendingR1(val prevTitle: String, val changedAt: Long)
    private data class PendingR2(val prevPkg: String, val leftAt: Long)
    private data class R3Window(val prevTitle: String, val changedAt: Long)

    private var pendingR1: PendingR1? = null
    private var pendingR2: PendingR2? = null
    private var r3: R3Window? = null
    private var quietUntil = Long.MIN_VALUE / 2

    /** 영상이 바뀐 뒤 말로 되돌릴 수 있는 유예 창. 대상은 정상 상태 영상이다. */
    data class Grace(val target: String, val mode: String, val until: Long)
    private var grace: Grace? = null
    private var adFirstSeen: Long? = null
    private var adLastSeen = Long.MIN_VALUE / 2
    private var adSuggested = false

    /** 자동 제안에 답이 없으면 사용자가 다시 움직일 때까지 제안하지 않는다. 영상을 틀어 둔 채 잠들면 자동재생마다 깨우게 된다. */
    private var pausedUntilUserAction = false
    /** 사용자가 마지막으로 있다는 걸 보인 시각. 화면 동작과 직접 호출을 함께 센다. */
    private var lastUserPresentTs = Long.MIN_VALUE / 2

    /** 말풍선이 떠 있는 동안에는 새 제안을 내지 않는다(한 번에 하나). */
    @Volatile
    var suggestionShowing: Boolean = false

    @Synchronized
    fun onEvent(e: ScreenEvent): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        if (e.pkg in config.ignoredPkgs) return out
        val user = isUserSignal(e)
        if (user) {
            lastUserActionTs = e.ts
            lastUserPresentTs = e.ts
            pausedUntilUserAction = false
        }
        if (e.kind == EventKind.BACK) {
            backPresses.addLast(e.ts)
            while (backPresses.size > 5) backPresses.removeFirst()
        }
        if (Marker.AUTOPLAY_COUNTDOWN in e.markers) lastCountdownTs = e.ts
        trackAd(e)

        val isLauncher = e.pkg in config.launcherPkgs
        if (e.kind == EventKind.WINDOW_CHANGED || foregroundPkg == null) {
            onForeground(e.pkg, isLauncher, e.ts, out)
        }

        if (e.pkg == config.youtubePkg) {
            // 제목만 있고 재생 중이 아닐 수 있다(일시정지·목록 보기). 재생 표시가 있을 때만 '보던 중'으로 본다.
            if (Marker.VIDEO_PLAYER in e.markers) lastVideoTs = e.ts
            val newTitle = e.title
            if (newTitle != null && newTitle != ytTitle) {
                val prev = ytTitle
                ytTitle = newTitle
                lastTitleChangeTs = e.ts
                if (prev != null) onTitleChanged(prev, newTitle, e.ts)
            }
        }

        // R3: 오조작 후 혼란 신호 — 뒤로 가기 연타
        r3?.let { w ->
            val recentBacks = backPresses.count { it >= e.ts - config.r3BackWithinMs && it >= w.changedAt }
            if (recentBacks >= config.r3BackCount) {
                emit(out, Suggestion.restoreVideo(RuleId.R3_MISTOUCH, w.prevTitle, "mistouch", e.ts))
                r3 = null
                pendingR2 = null
            }
        }
        // R1은 바뀐 뒤 사용자가 움직이면 취소한다.
        pendingR1?.let { if (user && e.ts > it.changedAt) pendingR1 = null }
        return out
    }

    /** 시간 경과로만 성립하는 조건(R1 10초, R2 20초, R3 만료, R4 5초)을 확인한다. 1초마다 부른다. */
    @Synchronized
    fun tick(now: Long = clock.now()): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        pendingR1?.let {
            if (now - it.changedAt >= config.r1WaitMs && lastUserActionTs <= it.changedAt) {
                // 말풍선이 떠 있어 못 냈으면 남겨 두었다가 다음 tick에 다시 시도한다.
                if (emit(out, Suggestion.restoreVideo(RuleId.R1_AUTOPLAY, it.prevTitle, "autoplay", now)) != Emit.DEFERRED) pendingR1 = null
            }
        }
        pendingR2?.let {
            if (now - it.leftAt >= config.r2WaitMs) {
                val s = Suggestion(
                    RuleId.R2_HOME_EXIT, it.prevPkg, RecipeIds.OPEN_PREVIOUS_APP,
                    mapOf("pkg" to it.prevPkg), "아까 보시던 유튜브로 돌아갈까요?", now,
                    headline = "홈 화면으로 나왔어요", yesLabel = "예, 돌아가 주세요",
                )
                if (emit(out, s) != Emit.DEFERRED) pendingR2 = null
            }
        }
        r3?.let { if (now - it.changedAt > config.r3WindowMs) r3 = null }
        val first = adFirstSeen
        if (first != null && !adSuggested) {
            if (now - adLastSeen > config.r4GoneMs) {
                adFirstSeen = null
            } else if (now - first >= config.r4WaitMs && lastUserActionTs < first) {
                val s = Suggestion(
                    RuleId.R4_AD_SKIP, "ad:${ytTitle ?: "-"}", RecipeIds.SKIP_AD, emptyMap(),
                    "광고 넘길까요?", now, headline = "광고가 나오고 있어요",
                    yesLabel = "예, 넘겨 주세요", noLabel = "아니요, 볼게요",
                )
                if (emit(out, s) != Emit.DEFERRED) adSuggested = true
            }
        }
        return out
    }

    /**
     * 레시피가 끝난 뒤 판단을 비우고, 잠시 동안의 제목 변화는 머고가 만든 것으로 보고 분류하지 않는다.
     * (에뮬레이터 확인: 복구 직후 MediaSession 제목이 1초쯤 늦게 바뀌어 '사용자가 바꿈'으로 잘못 읽혔다.)
     */
    @Synchronized
    fun afterAutomation(now: Long, quietMs: Long = 5_000) {
        reset()
        quietUntil = now + quietMs
    }

    /** 지금 열려 있는 유예 창. 시간이 지났거나 이미 정상 상태 영상으로 돌아왔으면 null. */
    @Synchronized
    fun grace(now: Long = clock.now()): Grace? {
        val g = grace ?: return null
        if (now > g.until || ytTitle == g.target) {
            grace = null
            return null
        }
        return g
    }

    /**
     * 제안에 답이 없을 때 부른다. 말풍선을 띄운 [since] 뒤로 사용자가 움직인 적이 없을 때만,
     * 사용자 동작이나 [resumeSuggestions]가 오기 전까지 새 제안을 내지 않는다.
     */
    @Synchronized
    fun pauseIfNoUserSince(since: Long) {
        pausedUntilUserAction = lastUserPresentTs < since
    }

    /** 사용자가 머고를 직접 불렀을 때처럼, 화면 이벤트 밖에서 사용자가 있다는 것을 알았을 때 부른다. */
    @Synchronized
    fun resumeSuggestions(now: Long = clock.now()) {
        lastUserPresentTs = now
        pausedUntilUserAction = false
    }

    /** 진행 중이던 판단을 모두 비운다. */
    @Synchronized
    fun reset() {
        grace = null
        pendingR1 = null
        pendingR2 = null
        r3 = null
        backPresses.clear()
    }

    private fun onTitleChanged(prev: String, new: String, ts: Long) {
        if (ts < quietUntil) return
        val baseline = baselineTitle()
        val sinceAction = ts - lastUserActionTs
        val countdownSeen = ts - lastCountdownTs <= config.countdownLookbackMs
        val noTouch = sinceAction > config.noActionBeforeChangeMs
        // 에뮬레이터 확인(2026-10-30): YouTube 플레이어 탭은 클릭 이벤트가 오지 않는다. 그래서 '터치가 있었다'는 증거로
        // 쓸 수 없고, 카운트다운이 있으면 자동재생, 없으면 사용자가 바꾼 것으로 본다.
        val autoplay = if (config.requireCountdown) countdownSeen && noTouch else noTouch
        if (baseline != null && baseline != new) {
            grace = Grace(baseline, if (autoplay) "autoplay" else "mistouch", ts + config.graceMs)
        }
        if (autoplay) {
            // R1: 이전 영상이 정상 상태(30초 이상 본 영상)여야 한다.
            if (baseline != null && baseline == prev) pendingR1 = PendingR1(prev, ts)
        } else {
            // R3: 사용자가 바꿨다. 이 시점에는 개입하지 않고 30초 동안 혼란 신호를 지켜본다.
            val target = baseline ?: return
            if (target != new) r3 = R3Window(target, ts)
        }
    }

    /** YouTube는 새 영상이 뜰 때 목록을 스스로 스크롤한다. 제목이 바뀐 직후 3초 안의 스크롤은 사용자 동작으로 세지 않는다. */
    private fun isUserSignal(e: ScreenEvent): Boolean =
        e.userAction && !(e.kind == EventKind.SCROLL && e.ts - lastTitleChangeTs <= AUTO_SCROLL_MS)

    private fun onForeground(pkg: String, isLauncher: Boolean, ts: Long, out: MutableList<Suggestion>) {
        val prev = foregroundPkg
        if (pkg == prev) return
        foregroundPkg = pkg
        if (isLauncher) {
            val w = r3
            // 보던 영상이 이미 정상 상태 영상이면 오조작이 아니라 그냥 홈으로 나간 것(R2)이다.
            if (w != null && ytTitle != w.prevTitle) {
                // R3: 오조작 뒤 홈으로 나감 — R2보다 우선한다.
                emit(out, Suggestion.restoreVideo(RuleId.R3_MISTOUCH, w.prevTitle, "mistouch", ts))
                r3 = null
                pendingR2 = null
                return
            }
            r3 = null
            if (prev == config.youtubePkg && ts - lastVideoTs <= config.r2RecentVideoMs) {
                pendingR2 = PendingR2(prev, ts)
            }
        } else {
            pendingR2 = null
        }
    }

    private fun trackAd(e: ScreenEvent) {
        if (Marker.AD_SKIP_VISIBLE in e.markers) {
            if (adFirstSeen == null || e.ts - adLastSeen > config.r4GoneMs) {
                adFirstSeen = e.ts
                adSuggested = false
            }
            adLastSeen = e.ts
        }
    }

    private enum class Emit { EMITTED, BLOCKED, DEFERRED }

    private companion object {
        const val AUTO_SCROLL_MS = 3_000L
    }

    private fun emit(out: MutableList<Suggestion>, s: Suggestion): Emit {
        if (suggestionShowing || out.isNotEmpty()) return Emit.DEFERRED
        if (pausedUntilUserAction) return Emit.BLOCKED
        if (cooldown.isBlocked(s.rule, s.target, s.createdAt)) return Emit.BLOCKED
        out += s
        return Emit.EMITTED
    }
}
