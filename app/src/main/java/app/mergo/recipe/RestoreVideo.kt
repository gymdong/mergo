package app.mergo.recipe

import app.mergo.model.FailReason
import app.mergo.model.Marker
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.RecipeOutcome
import app.mergo.model.ScreenAgent
import app.mergo.model.ScreenReader
import app.mergo.service.YouTubeSignals
import kotlinx.coroutines.delay

/**
 * 이전 영상으로 되돌린다. 접근성으로는 영상 ID를 얻을 수 없어 제목으로 찾는다.
 *
 * - 자동재생(S1): 이전 동영상 버튼 → 시청 기록 → 화면 조작 에이전트
 * - 오조작(S2): 시청 기록 → 이전 동영상 버튼 → 화면 조작 에이전트 (오조작은 이전 버튼이 없을 수 있다)
 */
class RestoreVideo(
    private val screen: ScreenReader,
    private val device: DeviceActions,
    private val currentTitle: () -> String?,
    private val agent: ScreenAgent,
    // 복구 10초 목표(AC-T1)를 지키려고 단계마다 짧게 확인한다. 광고가 나오는 시간은 이 한도에 세지 않는다.
    private val verifyTimeoutMs: Long = 3_000,
    private val adMaxMs: Long = 35_000,
    private val pollMs: Long = 300,
) : Recipe {
    override val id = RecipeIds.RESTORE_VIDEO

    override suspend fun run(params: Map<String, String>): RecipeOutcome {
        // 돌아갈 정상 상태 영상이 없으면 아무것도 누르지 않는다.
        val title = params["title"]?.takeIf { it.isNotBlank() } ?: return RecipeOutcome.Failed(FailReason.PRECONDITION)
        // 제목을 읽지 못하면(null) 실패가 아니라 검증 불가다.
        val verify: suspend () -> Boolean? = { awaitTitle(title) }
        val previous = Step("previous_button", { pressPrevious() }, verify)
        val history = Step("watch_history", { openFromHistory(title) }, verify, cleanup = { leaveHistory() })
        val byAgent = Step("screen_agent", { agent.achieve("'$title' 영상 재생") }, verify)
        // 이미 그 영상을 보고 있으면 아무것도 누르지 않는다.
        if (device.foregroundPkg() == YouTubeSignals.PKG && YouTubeSignals.sameTitle(currentTitle(), title)) {
            return RecipeOutcome.Verified(acted = false)
        }
        val steps = if (params["mode"] == "mistouch") listOf(history, previous, byAgent) else listOf(previous, history, byAgent)
        return StepChain.run(id, steps)
    }

    /**
     * 영상이 열려 제목이 같아질 때까지 기다린다. 열린 영상 앞에 광고가 붙으면(머고가 복구하는 동안은 광고 자동 넘기기가
     * 꺼져 있다) 건너뛰기 버튼을 누르고, 버튼이 없으면 광고가 끝나기를 [adMaxMs]까지 기다린다.
     * 제목을 한 번도 읽지 못했으면 null(검증 불가).
     */
    private suspend fun awaitTitle(title: String): Boolean? {
        var quiet = 0L
        var ad = 0L
        var read = false
        while (true) {
            val now = currentTitle()
            if (now != null) {
                read = true
                if (YouTubeSignals.sameTitle(now, title)) return true
            }
            val nodes = runCatching { screen.nodes() }.getOrDefault(emptyList())
            val markers = YouTubeSignals.markers(nodes)
            if (Marker.AD_SHOWING in markers || Marker.AD_SKIP_VISIBLE in markers) {
                val player = YouTubeSignals.playerNode(nodes)
                nodes.firstOrNull { YouTubeSignals.isSkipButton(it, player) }?.let { screen.click(it.id) }
                if (ad >= adMaxMs) return if (read) false else null
                ad += pollMs
            } else {
                if (quiet >= verifyTimeoutMs) return if (read) false else null
                quiet += pollMs
            }
            delay(pollMs)
        }
    }

    private suspend fun ensureYouTube() {
        if (device.foregroundPkg() != YouTubeSignals.PKG) {
            device.launchApp(YouTubeSignals.PKG)
            delay(1_500)
        }
    }

    private suspend fun pressPrevious(): Boolean {
        ensureYouTube()
        var prev = YouTubeSignals.previousButton(screen.nodes())
        if (prev == null) {
            // 컨트롤이 숨어 있으면 플레이어를 한 번 눌러 띄운다.
            // 플레이어를 못 찾으면 아무 데나 누르지 않는다(목록에서 엉뚱한 영상이 열릴 수 있다).
            val player = YouTubeSignals.playerNode(screen.nodes()) ?: return false
            screen.tap(player.bounds.centerX, player.bounds.centerY)
            delay(600)
            prev = YouTubeSignals.previousButton(screen.nodes())
        }
        return prev != null && screen.click(prev.id)
    }

    private suspend fun openFromHistory(title: String): Boolean {
        if (!device.openUri(HISTORY_URI, YouTubeSignals.PKG)) return false
        // 기록 목록이 그려질 때까지 기다린다.
        var item = null as app.mergo.model.NodeSnapshot?
        for (i in 0 until 6) {
            delay(400)
            item = screen.nodes().firstOrNull { it.visible && YouTubeSignals.sameTitle(it.text ?: it.desc, title) }
            if (item != null) break
        }
        if (item == null) {
            // 원래 화면을 더 망가뜨리지 않게, 기록 화면이 실제로 열려 있을 때만 닫는다.
            leaveHistory()
            return false
        }
        return screen.click(item.id)
    }

    private fun leaveHistory() {
        val onHistory = screen.nodes().any { n -> listOfNotNull(n.text, n.desc).any { YouTubeSignals.historyHeading.matches(it.trim()) } }
        if (onHistory) screen.back()
    }

    companion object {
        const val HISTORY_URI = "https://www.youtube.com/feed/history"
    }
}
