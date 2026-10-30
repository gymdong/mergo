package app.mergo.recipe

import app.mergo.memory.BaselineStore
import app.mergo.model.FailReason
import app.mergo.model.KnownApps
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.RecipeOutcome
import app.mergo.model.ScreenReader
import app.mergo.model.VoiceIO
import app.mergo.service.YouTubeSignals
import kotlinx.coroutines.delay

/** S4: 정상 상태의 볼륨으로 되돌린다. 정상 상태가 0이면 마지막으로 소리가 나던 볼륨, 그것도 없으면 최대의 절반. */
class SetVolume(private val device: DeviceActions, private val baseline: BaselineStore) : Recipe {
    override val id = RecipeIds.SET_VOLUME

    fun target(): Int {
        val fromBaseline = baseline.current?.volume?.takeIf { it > 0 }
        return fromBaseline ?: baseline.lastAudibleVolume ?: (device.maxVolume() / 2).coerceAtLeast(1)
    }

    override suspend fun run(params: Map<String, String>): RecipeOutcome {
        val target = params["volume"]?.toIntOrNull()?.takeIf { it > 0 } ?: target()
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
        val said = "광고가 끝나면 이어서 나와요"
        voice.speak(said)
        return RecipeOutcome.Guided(said)
    }
}
