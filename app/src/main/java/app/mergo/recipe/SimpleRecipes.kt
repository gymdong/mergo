package app.mergo.recipe

import app.mergo.memory.BaselineStore
import app.mergo.model.FailReason
import app.mergo.model.KnownApps
import app.mergo.model.NodeSnapshot
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.RecipeOutcome
import app.mergo.model.ScreenReader
import app.mergo.model.VoiceIO
import app.mergo.service.YouTubeSignals
import kotlinx.coroutines.delay

/**
 * S4: 정상 상태의 볼륨으로 되돌린다. 정상 상태가 0이면 마지막으로 소리가 나던 볼륨, 그것도 없으면 최대의 절반.
 * 이미 그 볼륨이면 바꿀 게 없으니 고쳤다고 하지 않고 다른 원인을 안내한다([RecipeOutcome.Guided]).
 */
class SetVolume(private val device: DeviceActions, private val baseline: BaselineStore) : Recipe {
    override val id = RecipeIds.SET_VOLUME

    fun target(): Int {
        val fromBaseline = baseline.current?.volume?.takeIf { it > 0 }
        return fromBaseline ?: baseline.lastAudibleVolume ?: (device.maxVolume() / 2).coerceAtLeast(1)
    }

    override suspend fun run(params: Map<String, String>): RecipeOutcome {
        val target = params["volume"]?.toIntOrNull()?.takeIf { it > 0 } ?: target()
        if (device.volume() == target) return RecipeOutcome.Guided(SOUND_IS_ON)
        return StepChain.run(
            id,
            listOf(Step("set_stream_music", { device.setVolume(target); true }, {
                Verifier.waitUntil(2_000) { device.volume() == target && device.volume() > 0 }
            })),
        )
    }
}

/**
 * S3: 직전 앱을 다시 연다. params["app"]이 있으면 [KnownApps]의 그 앱을 연다("유튜브 어딨어").
 * 이름으로 여는 앱은 허용 목록에 있는 것만이고, 기기마다 다른 패키지를 앞에서부터 시도한다.
 */
class OpenPreviousApp(private val device: DeviceActions, private val baseline: BaselineStore) : Recipe {
    override val id = RecipeIds.OPEN_PREVIOUS_APP

    override suspend fun run(params: Map<String, String>): RecipeOutcome {
        params["app"]?.takeIf { it.isNotBlank() }?.let { appId ->
            val app = KnownApps.byId(appId) ?: return RecipeOutcome.Failed(FailReason.PRECONDITION)
            // 설치된 첫 패키지 하나만 연다. 열린 뒤 확인이 늦다고 다음 후보까지 열면 앱이 두 개 뜬다.
            var opened: String? = null
            val launch = Step(
                "launch:${app.id}",
                { opened = app.pkgs.firstOrNull { device.launchApp(it) }; opened != null },
                { Verifier.waitFor(4_000) { device.foregroundPkg()?.let { it == opened } } },
            )
            return StepChain.run(id, listOf(launch))
        }
        val pkg = (params["pkg"]?.takeIf { it.isNotBlank() } ?: baseline.current?.pkg)?.takeIf { it.isNotBlank() }
            ?: return RecipeOutcome.Failed(FailReason.PRECONDITION)
        return StepChain.run(id, listOf(launch(pkg)))
    }

    private fun launch(pkg: String) =
        Step("launch:$pkg", { device.launchApp(pkg) }, { Verifier.waitFor(4_000) { device.foregroundPkg()?.let { it == pkg } } })
}

/**
 * S8: 인터넷 패널을 열어 비행기 모드면 끄고, 와이파이가 꺼져 있으면 켠다. 10초 안에 연결되면 성공이다.
 * 패널(Android 12+ 시스템 인터넷 창)의 단추는 viewId로 찾는다. 와이파이 스위치는 켜짐 여부를 노드로 알 수 없어
 * 시스템에 먼저 묻고, 꺼져 있을 때만 누른다. 단추를 못 찾으면 패널을 열어 둔 채 직접 누르도록 안내한다.
 */
class OpenInternetPanel(
    private val screen: ScreenReader,
    private val device: DeviceActions,
    private val onlineWaitMs: Long = 10_000,
) : Recipe {
    override val id = RecipeIds.OPEN_INTERNET_PANEL

    override suspend fun run(params: Map<String, String>): RecipeOutcome {
        if (device.isOnline()) return RecipeOutcome.Guided("인터넷은 연결돼 있어요")
        if (!device.openInternetPanel()) return RecipeOutcome.Failed(FailReason.NOT_REACHED)
        var panelPkg: String? = null
        var acted = false
        find(AIRPLANE_BUTTON)?.let {
            panelPkg = it.pkg()
            acted = screen.click(it.id)
            // 비행기 모드를 끄면 전에 켜 두었던 와이파이가 스스로 켜지기도 한다. 그걸 기다린 뒤에 스위치를 볼지 정한다.
            Verifier.waitUntil(WIFI_SETTLE_MS) { device.isWifiEnabled() }
        }
        if (!device.isWifiEnabled()) {
            // 패널이 다시 그려졌을 수 있어 새로 찾고, 누르기 직전에 한 번 더 확인한다.
            find(WIFI_TOGGLE)?.takeIf { !device.isWifiEnabled() }?.let {
                panelPkg = it.pkg()
                acted = screen.click(it.id) || acted
            }
        } else if (!acted) {
            closePanel(panelPkg ?: find(WIFI_TOGGLE)?.pkg())
            return RecipeOutcome.Guided("와이파이는 켜져 있는데 인터넷이 안 돼요. 공유기를 확인해 주세요.")
        }
        if (!acted) return RecipeOutcome.Guided("화면에서 와이파이를 눌러 켜 주세요")
        if (!Verifier.waitUntil(onlineWaitMs) { device.isOnline() }) return RecipeOutcome.Failed(FailReason.NOT_REACHED)
        closePanel(panelPkg)
        return RecipeOutcome.Verified()
    }

    /** 패널이 아직 앞에 있을 때만 닫는다. 사용자가 먼저 닫았으면 뒤로 가기가 엉뚱한 앱을 닫는다. */
    private fun closePanel(panelPkg: String?) {
        if (panelPkg != null && screen.currentPackage() == panelPkg) screen.back()
    }

    private fun NodeSnapshot.pkg(): String? = viewId?.substringBefore(":id/", "")?.takeIf { it.isNotEmpty() }

    /** 패널이 뜰 때까지 잠시 기다리며 viewId가 [suffix]로 끝나는 노드를 찾는다. */
    private suspend fun find(suffix: String): NodeSnapshot? {
        repeat(10) {
            screen.nodes().firstOrNull { it.viewId?.endsWith(suffix) == true }?.let { return it }
            delay(300)
        }
        return null
    }

    private companion object {
        const val AIRPLANE_BUTTON = ":id/apm_button"
        const val WIFI_SETTLE_MS = 3_000L
        const val WIFI_TOGGLE = ":id/wifi_toggle"
    }
}

/** 볼륨이 이미 켜져 있을 때의 안내. 출력 장치·재생 상태는 아직 읽지 못해 둘 다 말한다. */
const val SOUND_IS_ON = "소리는 켜져 있어요. 영상이 멈췄는지, 이어폰이 연결됐는지 봐 주세요."

/**
 * S11: 광고 건너뛰기 버튼을 누른다. 버튼이 아직 없으면 최대 10초 기다린다.
 * 건너뛸 수 없는 광고면 안내만 하고 끝낸다([RecipeOutcome.Guided]). 해결이 아니지만 가족 알림으로 넘기지도 않는다.
 */
class SkipAd(
    private val screen: ScreenReader,
    private val voice: VoiceIO,
    private val waitMs: Long = 10_000,
    private val pollMs: Long = 500,
) : Recipe {
    override val id = RecipeIds.SKIP_AD

    override suspend fun run(params: Map<String, String>): RecipeOutcome {
        var waited = 0L
        var told = false
        while (waited <= waitMs) {
            val nodes = screen.nodes()
            val player = YouTubeSignals.playerNode(nodes)
            val skip = nodes.firstOrNull { YouTubeSignals.isSkipButton(it, player) }
            if (skip != null) {
                screen.click(skip.id)
                // 창이 바뀌는 중이면 노드가 비어 온다. 빈 화면을 '버튼이 사라졌다'로 읽지 않는다.
                val gone = Verifier.waitFor(3_000) {
                    screen.nodes().takeIf { it.isNotEmpty() }?.none { YouTubeSignals.isSkipButton(it) }
                }
                return when (gone) {
                    true -> RecipeOutcome.Verified()
                    false -> RecipeOutcome.Failed(FailReason.NOT_REACHED)
                    null -> RecipeOutcome.Unverifiable("건너뛰기 뒤 화면을 읽지 못함")
                }
            }
            if (!told) {
                voice.speak("잠시만요, 곧 넘길 수 있어요")
                told = true
            }
            delay(pollMs)
            waited += pollMs
        }
        return RecipeOutcome.Guided("광고가 끝나면 이어서 나와요")
    }
}
