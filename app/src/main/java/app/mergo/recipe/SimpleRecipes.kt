package app.mergo.recipe

import app.mergo.memory.BaselineStore
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
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

    override suspend fun run(params: Map<String, String>): Boolean {
        val target = params["volume"]?.toIntOrNull()?.takeIf { it > 0 } ?: target()
        return StepChain.run(
            id,
            listOf(Step("set_stream_music", { device.setVolume(target); true }, {
                Verifier.waitUntil(2_000) { device.volume() == target && device.volume() > 0 }
            })),
        )
    }
}

/** S3: 직전 앱을 다시 연다. */
class OpenPreviousApp(private val device: DeviceActions, private val baseline: BaselineStore) : Recipe {
    override val id = RecipeIds.OPEN_PREVIOUS_APP

    override suspend fun run(params: Map<String, String>): Boolean {
        val pkg = params["pkg"] ?: baseline.current?.pkg ?: return false
        return StepChain.run(
            id,
            listOf(Step("launch", { device.launchApp(pkg) }, { Verifier.waitUntil(4_000) { device.foregroundPkg() == pkg } })),
        )
    }
}

/**
 * S11: 광고 건너뛰기 버튼을 누른다. 버튼이 아직 없으면 최대 10초 기다린다.
 * 건너뛸 수 없는 광고면 안내만 하고 끝낸다. 이 경우도 처리한 것으로 보고 가족 알림으로 넘기지 않는다.
 */
class SkipAd(
    private val screen: ScreenReader,
    private val voice: VoiceIO,
    private val waitMs: Long = 10_000,
    private val pollMs: Long = 500,
) : Recipe {
    override val id = RecipeIds.SKIP_AD

    override suspend fun run(params: Map<String, String>): Boolean {
        var waited = 0L
        var told = false
        while (waited <= waitMs) {
            val nodes = screen.nodes()
            val player = YouTubeSignals.playerNode(nodes)
            val skip = nodes.firstOrNull { YouTubeSignals.isSkipButton(it, player) }
            if (skip != null) {
                screen.click(skip.id)
                return Verifier.waitUntil(3_000) { screen.nodes().none { YouTubeSignals.isSkipButton(it) } }
            }
            if (!told) {
                voice.speak("잠시만요, 곧 넘길 수 있어요")
                told = true
            }
            delay(pollMs)
            waited += pollMs
        }
        voice.speak("광고가 끝나면 이어서 나와요")
        return true
    }
}
