package app.mergo.core

import android.util.Log
import app.mergo.detect.Cooldown
import app.mergo.detect.StuckDetector
import app.mergo.detect.Suggestion
import app.mergo.memory.BaselineStore
import app.mergo.memory.FamilyLog
import app.mergo.memory.TimelineBuffer
import app.mergo.model.Baseline
import app.mergo.model.Brain
import app.mergo.model.BrainContext
import app.mergo.model.Clock
import app.mergo.model.Marker
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.ScreenEvent
import app.mergo.model.SettingsSource
import app.mergo.model.VoiceIO
import app.mergo.model.YesNo
import app.mergo.ui.OverlayPort
import app.mergo.ui.Prompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 감지 → 제안 → 동의 → 복구 → 확인의 흐름을 잇는다. 안드로이드 타입에 직접 의존하지 않는다.
 *
 * 레시피가 화면을 조작하는 동안 생긴 이벤트는 사용자 동작으로 세지 않는다(userAction=false).
 */
class MergoController(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val timeline: TimelineBuffer,
    private val baseline: BaselineStore,
    private val detector: StuckDetector,
    private val cooldown: Cooldown,
    private val recipes: Map<String, Recipe>,
    private val brain: Brain,
    private val voice: VoiceIO,
    private val overlay: OverlayPort,
    private val settings: SettingsSource,
    private val familyLog: FamilyLog,
    private val liveVolume: () -> Int,
    private val liveOnline: () -> Boolean,
) {
    private val busy = AtomicBoolean(false)

    @Volatile
    var automating: Boolean = false
        private set

    @Volatile
    private var lastMarkers: Set<Marker> = emptySet()

    var log: (String) -> Unit = { runCatching { Log.i("Mergo", it) } }

    fun onScreenEvent(raw: ScreenEvent) {
        val e = if (automating) raw.copy(userAction = false) else raw
        timeline.add(e)
        baseline.onScreen(e)
        if (e.pkg == app.mergo.service.YouTubeSignals.PKG) lastMarkers = e.markers
        handle(detector.onEvent(e))
    }

    fun onDeviceState(volume: Int, online: Boolean, userAction: Boolean) {
        baseline.onDevice(volume, online, clock.now(), userAction && !automating)
    }

    /** 1초마다 부른다. */
    fun tick() {
        val now = clock.now()
        baseline.tick(now)
        handle(detector.tick(now))
    }

    private fun handle(suggestions: List<Suggestion>) {
        if (automating) return
        val s = suggestions.firstOrNull() ?: return
        // 코루틴이 시작되기 전에 다음 제안이 나오지 않도록 바로 막는다.
        detector.suggestionShowing = true
        scope.launch { onSuggestion(s) }
    }

    suspend fun onSuggestion(s: Suggestion) {
        if (!busy.compareAndSet(false, true)) return
        detector.suggestionShowing = true
        val shownAt = clock.now()
        try {
            log("제안 ${s.rule} target=${s.target} recipe=${s.recipeId} params=${s.params}")
            val prompt = Prompt(s.headline, s.say, eyebrow = "머고가 알아챘어요", yes = s.yesLabel, no = s.noLabel)
            val answer = if (settings.autoExecute) YesNo.YES else confirm(prompt)
            log("응답 ${s.rule}: $answer")
            if (answer == YesNo.YES) {
                execute(s.recipeId, s.params, s.say)
            } else {
                // 거절이나 무응답: 같은 대상에는 다시 제안하지 않고, 지금 상태를 정상 상태로 인정한다.
                cooldown.reject(s.rule, s.target, clock.now())
                baseline.acceptCurrent(clock.now())
                if (answer == YesNo.NO) voice.speak("알겠어요")
                // 무응답은 못 들었거나 자는 중일 수 있다. 말풍선이 떠 있는 동안에도 움직임이 없었으면
                // 사용자가 다시 움직일 때까지 자동 제안을 멈춘다.
                if (answer == YesNo.NONE) detector.pauseIfNoUserSince(shownAt)
            }
        } finally {
            detector.suggestionShowing = false
            busy.set(false)
        }
    }

    /** 도와줘 버튼(또는 호출어)으로 불렀을 때. [utterance]가 있으면 듣기를 건너뛴다. */
    suspend fun onHelp(utterance: String? = null) {
        // 직접 불렀으니 사용자가 깨어 있다. 무응답으로 멈춘 자동 제안을 다시 켠다.
        detector.resumeSuggestions()
        if (!busy.compareAndSet(false, true)) return
        detector.suggestionShowing = true
        try {
            val said = utterance ?: askWhatHappened() ?: run {
                overlay.dismiss()
                return
            }
            log("도와줘 발화: $said")
            val started = clock.now()
            val decision = brain.decide(brainContext(said))
            log("Brain ${clock.now() - started}ms: $decision")
            if (decision.recipe == RecipeIds.ASK || decision.recipe !in recipes) {
                voice.speak(decision.say)
                return
            }
            if (decision.confirm && !settings.autoExecute) {
                if (confirm(Prompt(decision.say, eyebrow = "머고", yes = "예, 해 주세요", no = "아니요")) != YesNo.YES) {
                    voice.speak("알겠어요")
                    return
                }
            } else {
                voice.speak(decision.say)
            }
            execute(decision.recipe, decision.params, said)
        } finally {
            detector.suggestionShowing = false
            busy.set(false)
        }
    }

    fun brainContext(utterance: String?): BrainContext {
        val now = Baseline(
            pkg = baseline.livePkg() ?: "",
            mediaTitle = baseline.liveTitle(),
            volume = liveVolume(),
            online = liveOnline(),
            updatedAt = clock.now(),
        )
        return BrainContext(
            utterance = utterance,
            diff = DiffBuilder.diff(baseline.current, now),
            timeline = timeline.summaryLines(),
            current = now,
            // 광고 표시는 YouTube 화면에서만 갱신된다. 다른 앱에 있으면 예전 YouTube 광고로 판단하지 않는다.
            screenHasAd = now.pkg == app.mergo.service.YouTubeSignals.PKG &&
                (Marker.AD_SKIP_VISIBLE in lastMarkers || Marker.AD_SHOWING in lastMarkers),
        )
    }

    private suspend fun execute(recipeId: String, params: Map<String, String>, situation: String) {
        val recipe = recipes[recipeId]
        val started = clock.now()
        val text = Texts.of(recipeId)
        automating = true
        val ok = try {
            overlay.progress(text.progress)
            voice.speak(text.progress)
            recipe != null && runCatching { recipe.run(enrich(recipeId, params)) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log("레시피 오류 $recipeId: ${it.message}") }
                .getOrDefault(false)
        } finally {
            automating = false
            overlay.progress(null)
            detector.afterAutomation(clock.now())
        }
        log("복구 $recipeId ok=$ok ${clock.now() - started}ms")
        if (ok) {
            overlay.done(text.done, "이제 편하게 이어서 보세요.")
            voice.speak(text.done)
            // 시안 05: 원래 상태가 맞는지 한 번 더 묻는다. 무응답은 맞는 것으로 본다(어르신 터치 1회 목표 유지).
            val right = if (settings.autoExecute) YesNo.YES else confirm(Prompt("이 화면이 맞나요?", yes = "예, 맞아요", no = "아니요"), DONE_CHECK_TIMEOUT_MS)
            if (right != YesNo.NO) {
                familyLog.add(FamilyLog.Entry(clock.now(), situation, recipeId, notified = false, resolved = true, summary = text.log))
                return
            }
        }
        val tellFamily = if (settings.autoExecute) {
            YesNo.YES
        } else {
            confirm(Prompt("제가 해결하지 못했어요", "가족에게 알릴까요?", yes = "예, 알려 주세요", no = "아니요"))
        }
        val notified = tellFamily == YesNo.YES
        familyLog.add(FamilyLog.Entry(clock.now(), situation, recipeId, notified = notified, resolved = false, summary = text.failLog))
        if (notified) voice.speak("가족에게 알렸어요")
    }

    /** 말로 받은 복구에 빠진 값을 정상 상태로 채운다. 예: "아까 보던 거" → 정상 상태의 영상 제목. */
    private fun enrich(recipeId: String, params: Map<String, String>): Map<String, String> = when (recipeId) {
        RecipeIds.RESTORE_VIDEO ->
            if (params["title"].isNullOrBlank()) params + ("title" to (baseline.current?.mediaTitle ?: "")) else params
        RecipeIds.OPEN_PREVIOUS_APP ->
            if (params["pkg"].isNullOrBlank()) params + ("pkg" to (baseline.current?.pkg ?: "")) else params
        else -> params
    }

    /** 말풍선 버튼과 음성 중 먼저 들어온 답을 쓴다. 음성이 못 알아들으면 버튼을 15초까지 기다린다. */
    suspend fun confirm(prompt: Prompt, timeoutMs: Long = BUBBLE_TIMEOUT_MS): YesNo = coroutineScope {
        val buttons = async { overlay.ask(prompt, timeoutMs) }
        val spoken = async {
            voice.speak(listOfNotNull(prompt.title, prompt.body).joinToString(" "))
            voice.listenYesNo(LISTEN_TIMEOUT_MS)
        }
        val first = select<Pair<Boolean, YesNo>> {
            buttons.onAwait { true to it }
            spoken.onAwait { false to it }
        }
        val answer = if (!first.first && first.second == YesNo.NONE) buttons.await() else first.second
        buttons.cancel()
        spoken.cancel()
        voice.cancelListening()
        overlay.dismiss()
        answer
    }

    private suspend fun askWhatHappened(): String? = coroutineScope {
        val tapped = async { overlay.choose("무엇을 도와드릴까요?", HELP_CHOICES, BUBBLE_TIMEOUT_MS) }
        val spoken = async {
            voice.speak("무엇을 도와드릴까요?")
            voice.listen(LISTEN_TIMEOUT_MS)
        }
        val first = select<Pair<Boolean, String?>> {
            tapped.onAwait { true to it }
            spoken.onAwait { false to it }
        }
        val answer = if (!first.first && first.second.isNullOrBlank()) tapped.await() else first.second
        tapped.cancel()
        spoken.cancel()
        voice.cancelListening()
        overlay.dismiss()
        answer
    }

    fun launchHelp(): Job = scope.launch { onHelp() }

    companion object {
        const val BUBBLE_TIMEOUT_MS = 15_000L
        const val LISTEN_TIMEOUT_MS = 5_000L
        const val DONE_CHECK_TIMEOUT_MS = 10_000L
        val HELP_CHOICES = listOf("아까 보던 거", "소리가 안 나", "이거 안 꺼져", "광고 안 꺼져")
    }
}
