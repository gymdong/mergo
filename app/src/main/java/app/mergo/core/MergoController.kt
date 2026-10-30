package app.mergo.core

import android.util.Log
import app.mergo.detect.Cooldown
import app.mergo.detect.StuckDetector
import app.mergo.detect.Suggestion
import app.mergo.ai.KeywordRules
import app.mergo.ai.YesNoParser
import app.mergo.memory.BaselineStore
import app.mergo.memory.FamilyLog
import app.mergo.memory.TimelineBuffer
import app.mergo.model.Baseline
import app.mergo.model.Brain
import app.mergo.model.BrainContext
import app.mergo.model.BrainDecision
import app.mergo.model.Clock
import app.mergo.model.FailReason
import app.mergo.model.Marker
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.RecipeOutcome
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
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
    private val liveScreen: () -> LiveScreen = { LiveScreen(null, emptyList()) },
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
            val answer = if (settings.autoExecute || !s.askFirst) YesNo.YES else confirm(prompt)
            log("응답 ${s.rule}: $answer")
            if (answer == YesNo.YES) {
                execute(s.recipeId, s.params, s.say, quiet = !s.askFirst)
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
            val screen = liveScreen()
            if (SensitiveScreen.matches(screen)) {
                // 화면의 위험 버튼은 누르지 않는다. 다만 밝기·글자 크기는 화면 밖의 시스템 설정이라
                // 어르신이 직접 요청했다면 이 화면에서도 복구할 수 있어야 한다.
                if (utterance == null) {
                    overlay.notice(SENSITIVE_TITLE)
                }
                val said = utterance ?: askWhatHappened("화면이 어둡거나 글씨가 작으면 말씀해 주세요.")
                    ?: return guideOutOfSensitiveScreen(screen, null)
                if (!KeywordRules.isVisualRescueRequest(said)) return guideOutOfSensitiveScreen(screen, said)
                val decision = decide(said)
                if (decision.recipe !in DISPLAY_RECIPES) return guideOutOfSensitiveScreen(screen, said)
                return execute(decision.recipe, decision.params, said)
            }
            var said = utterance ?: run {
                var otherProblem: String? = null
                // 머고가 짐작 가는 게 있으면 그것 하나만 먼저 묻는다. 아니라고 하면 자유 발화부터 듣는다.
                guess()?.let { g ->
                    log("도와줘 추측: ${g.recipeId} ${g.params}")
                    val reply = confirmGuess(g.prompt)
                    otherProblem = reply.otherProblem
                    when (reply.answer) {
                        YesNo.YES -> return execute(g.recipeId, g.params, g.prompt.title)
                        YesNo.NONE -> {
                            if (otherProblem == null) {
                                overlay.dismiss()
                                return
                            }
                        }
                        YesNo.NO -> Unit
                    }
                }
                otherProblem ?: askWhatHappened()
            } ?: run {
                overlay.dismiss()
                return
            }
            var decision = decide(said)
            // 되물었는데 대답을 받지 않으면 거기서 끝나 버린다. 그 질문으로 선택지를 한 번 더 보여 준다.
            if (decision.recipe == RecipeIds.ASK && utterance == null) {
                said = askWhatHappened(decision.say) ?: run {
                    overlay.dismiss()
                    return
                }
                decision = decide(said)
            }
            if (decision.recipe == RecipeIds.ASK || decision.recipe !in recipes) {
                overlay.notice(decision.say)
                voice.speak(decision.say)
                return
            }
            if (decision.confirm && !settings.autoExecute) {
                if (confirm(Prompt(decision.say, eyebrow = "머고", yes = "응, 해 줘", no = "아니")) != YesNo.YES) {
                    voice.speak("알겠어요")
                    return
                }
            } else {
                if (decision.recipe !in DISPLAY_RECIPES) voice.speak(decision.say)
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

    /** [quiet]면 묻지 않고 한 일이라 말하지 않고, 완료 확인·가족 알림도 묻지 않는다. 결과는 글자 카드와 기록으로만 남긴다. */
    private suspend fun execute(
        recipeId: String, params: Map<String, String>, situation: String,
        quiet: Boolean = false, announceQuiet: Boolean = false,
        afterQuiet: (suspend () -> Unit)? = null,
    ) {
        val recipe = recipes[recipeId]
        val started = clock.now()
        val text = Texts.of(recipeId, params)
        automating = true
        val outcome = try {
            overlay.progress(text.progress)
            // 화면이 너무 어두우면 TTS 엔진 대기로 복구가 늦어져서는 안 된다. 먼저 설정을 고친 뒤 알린다.
            if (!quiet && recipeId !in DISPLAY_RECIPES) voice.speak(text.progress)
            if (recipe == null) {
                RecipeOutcome.Failed(FailReason.NO_RECIPE)
            } else {
                runCatching { recipe.run(enrich(recipeId, params)) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log("레시피 오류 $recipeId: ${it.message}") }
                    .getOrDefault(RecipeOutcome.Failed(FailReason.ERROR))
            }
        } finally {
            automating = false
            overlay.progress(null)
            detector.afterAutomation(clock.now())
        }
        log("복구 $recipeId $outcome ${clock.now() - started}ms")
        var label = outcome.label()
        fun record(resolved: Boolean, summary: String, notified: Boolean = false) =
            familyLog.add(FamilyLog.Entry(clock.now(), situation, recipeId, notified, resolved, summary, label))
        when (outcome) {
            is RecipeOutcome.Verified -> {
                overlay.done(text.done, "이제 편하게 이어서 보세요.")
                if (quiet) {
                    if (afterQuiet != null) afterQuiet() else if (announceQuiet) voice.speak(text.done)
                    return record(resolved = true, summary = text.log)
                }
                voice.speak(text.done)
                // 시안 05: 원래 상태가 맞는지 한 번 더 묻는다. 머고가 이미 확인했으므로 무응답은 맞는 것으로 본다(어르신 터치 1회 목표 유지).
                val right = if (settings.autoExecute) YesNo.YES else confirm(doneCheck(recipeId), DONE_CHECK_TIMEOUT_MS)
                if (right != YesNo.NO) return record(resolved = true, summary = text.log)
                // 머고는 맞다고 확인했는데 사용자는 아니라고 했다. 목표 자체를 잘못 골랐다는 신호라 따로 남긴다.
                label = "verified_rejected"
            }
            // 해결도 실패도 아니므로 가족 알림을 묻지 않는다. 안내 문장은 여기서 한 번만 말한다.
            is RecipeOutcome.Guided -> {
                overlay.notice(outcome.said)
                if (!quiet || announceQuiet) voice.speak(outcome.said)
                return record(resolved = false, summary = outcome.said)
            }
            is RecipeOutcome.Unverifiable -> {
                if (quiet) {
                    if (announceQuiet) voice.speak("설정을 바꿨지만 결과를 확인하지 못했어요")
                    return record(resolved = false, summary = UNCHECKED_LOG)
                }
                // 머고가 결과를 확인하지 못했으니 '다 됐어요'라고 하지 않는다. 사용자가 "예"라고 해야만 해결로 기록하고, 무응답은 미확인으로 둔다.
                // 확인 없이 실행 모드에서는 물을 수 없으니 미확인으로만 남긴다.
                val right = if (settings.autoExecute) {
                    YesNo.NONE
                } else {
                    voice.speak("제대로 됐는지 봐 주세요")
                    confirm(doneCheck(recipeId), DONE_CHECK_TIMEOUT_MS)
                }
                if (right == YesNo.YES) return record(resolved = true, summary = text.log)
                if (right == YesNo.NONE) return record(resolved = false, summary = UNCHECKED_LOG)
            }
            is RecipeOutcome.Failed -> if (quiet) {
                if (announceQuiet) voice.speak(text.failLog)
                return record(resolved = false, summary = text.failLog)
            }
        }
        val tellFamily = if (settings.autoExecute) {
            YesNo.YES
        } else {
            confirm(Prompt("제가 해결하지 못했어요", "가족에게 알릴까요?", yes = "응, 알려 줘", no = "아니"))
        }
        val notified = tellFamily == YesNo.YES
        record(resolved = false, summary = text.failLog, notified = notified)
        if (notified) voice.speak("가족에게 알렸어요")
    }

    /** 가족 기록에 남기는 결과 이름. 나중에 학습 라벨로 쓸 때 해결/안내/미확인/실패를 구분한다. */
    private fun RecipeOutcome.label(): String = when (this) {
        is RecipeOutcome.Verified -> if (acted) "verified" else "verified_noop"
        is RecipeOutcome.Guided -> "guided"
        is RecipeOutcome.Unverifiable -> "unverifiable"
        is RecipeOutcome.Failed -> "failed:${reason.name.lowercase()}"
    }

    /** 설치·결제·개인정보 화면에서는 대신 누르지 않는다. 빠져나오는 법만 알려 주고 가족 알림을 묻는다. */
    private suspend fun guideOutOfSensitiveScreen(screen: LiveScreen, utterance: String?) {
        log("민감 화면 ${screen.pkg}: 조작하지 않고 안내")
        val tell = confirm(Prompt(SENSITIVE_TITLE, SENSITIVE_BODY, yes = "응, 알려 줘", no = "아니"))
        val notified = tell == YesNo.YES
        familyLog.add(
            FamilyLog.Entry(
                clock.now(), utterance ?: "도와줘", SENSITIVE_RECIPE, notified, resolved = false,
                summary = "설치·결제 같은 화면이라 누르지 않고 안내했어요", outcome = "guided",
            ),
        )
        if (notified) voice.speak("가족에게 알렸어요")
    }

    private data class Guess(val recipeId: String, val params: Map<String, String>, val prompt: Prompt)

    /**
     * 도와줘를 눌렀을 때 먼저 물어볼 짐작 하나. 정상 상태와 지금 화면의 차이로 고르고, 근거가 없으면 null.
     * 어르신이 읽을 말은 짧게, 어르신이 누를 답은 반말(어르신의 말)로 쓴다.
     */
    private fun guess(): Guess? {
        val ctx = brainContext(null)
        val base = baseline.current
        val now = ctx.current
        val other = "아니, 다른 거"
        val video = base?.mediaTitle?.takeIf { now.mediaTitle != null && it != now.mediaTitle }
        val app = base?.pkg?.takeIf { it.isNotBlank() && now.pkg.isNotBlank() && it != now.pkg }
        return listOfNotNull(
            Guess(RecipeIds.SKIP_AD, emptyMap(), Prompt("광고 넘길까요?", yes = "응, 넘겨 줘", no = other)).takeIf { ctx.screenHasAd },
            video?.let { Guess(RecipeIds.RESTORE_VIDEO, mapOf("title" to it), Prompt("보던 거 다시 틀까요?", it, yes = "응, 틀어 줘", no = other)) },
            app?.let { Guess(RecipeIds.OPEN_PREVIOUS_APP, mapOf("pkg" to it), Prompt("보던 앱 다시 열까요?", yes = "응, 열어 줘", no = other)) },
            Guess(RecipeIds.SET_VOLUME, emptyMap(), Prompt("소리 다시 켤까요?", yes = "응, 켜 줘", no = other))
                .takeIf { now.volume == 0 && (base?.volume ?: 0) > 0 },
        ).firstOrNull { it.recipeId in recipes }
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

    private data class GuessReply(val answer: YesNo, val otherProblem: String? = null)
    private sealed interface GuessInput {
        data class Button(val answer: YesNo) : GuessInput
        data class Speech(val said: String?) : GuessInput
    }

    /** 짐작을 묻는 동안 어르신이 다른 문제를 말하면 다시 말하게 하지 않고 바로 그 말을 처리한다. */
    private suspend fun confirmGuess(prompt: Prompt): GuessReply = coroutineScope {
        val buttons = async { overlay.ask(prompt, BUBBLE_TIMEOUT_MS) }
        val spoken = async {
            // 버튼을 누르자마자 말씀해도 놓치지 않는다. 첫 듣기에 답이 없을 때만 음성으로 질문한다.
            voice.listen(LISTEN_TIMEOUT_MS) ?: run {
                voice.speak(listOfNotNull(prompt.title, prompt.body).joinToString(" "))
                voice.listen(LISTEN_TIMEOUT_MS)
            }
        }
        val first = select<GuessInput> {
            buttons.onAwait { GuessInput.Button(it) }
            spoken.onAwait { GuessInput.Speech(it) }
        }
        val reply = when (first) {
            is GuessInput.Button -> GuessReply(first.answer)
            is GuessInput.Speech -> {
                val said = first.said
                when {
                    said.isNullOrBlank() -> GuessReply(buttons.await())
                    KeywordRules.isVisualRescueRequest(said) -> GuessReply(YesNo.NONE, said)
                    else -> {
                        val answer = YesNoParser.parse(said)
                        if (answer == YesNo.NONE) GuessReply(answer, said) else GuessReply(answer)
                    }
                }
            }
        }
        buttons.cancel()
        spoken.cancel()
        voice.cancelListening()
        overlay.dismiss()
        reply
    }

    /** LLM을 부르면 몇 초 걸린다. 그동안 화면이 멈춘 것처럼 보이지 않게 한다. */
    private suspend fun decide(said: String): BrainDecision {
        log("도와줘 발화: $said")
        val started = clock.now()
        overlay.progress(THINKING)
        val decision = try {
            brain.decide(brainContext(said))
        } finally {
            overlay.progress(null)
        }
        log("Brain ${clock.now() - started}ms: $decision")
        return decision
    }

    private suspend fun askWhatHappened(question: String = "무엇을 도와드릴까요?"): String? = coroutineScope {
        // 추측 근거가 없을 때는 보기보다 말을 먼저 받는다.
        overlay.listening(question, ready = false)
        val firstSaid = voice.listen(LISTEN_TIMEOUT_MS) { overlay.listening(question, ready = true) }
        overlay.dismiss()
        if (!firstSaid.isNullOrBlank()) return@coroutineScope firstSaid

        // 말을 못 했거나 인식하지 못했을 때만 큰 선택지를 보조 경로로 보여 준다.
        val tapped = async { overlay.choose(question, HELP_CHOICES, BUBBLE_TIMEOUT_MS) }
        val spoken = async {
            voice.speak(question)
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

    /** 가족이 미리 허용한 경우에만, 급격히 낮아진 시각 설정을 먼저 복구한다. */
    fun launchAutomaticDisplayRescue(recipeId: String, stillNeeded: () -> Boolean = { true }): Job = scope.launch {
        if (recipeId !in DISPLAY_RECIPES) return@launch
        whenFree(stillNeeded) {
            execute(recipeId, mapOf("restore" to "true"), "시각 설정 자동 복구", quiet = true, announceQuiet = true)
        }
    }

    /**
     * 화면이 너무 어두우면 묻지 않고 먼저 [level]로 밝게 한 뒤 어떤지 묻는다. 싫다고 하면 [onDeclined]로 이 기능을 끈다.
     * 답이 없으면 못 봤을 수 있으니 그대로 둔다.
     */
    fun launchAutomaticBrightness(level: Int, stillNeeded: () -> Boolean, onDeclined: () -> Unit): Job = scope.launch {
        whenFree(stillNeeded) {
            val params = mapOf("level" to level.toString())
            execute(RecipeIds.BRIGHTEN_SCREEN, params, "화면이 너무 어두웠어요", quiet = true, announceQuiet = true) {
                if (confirm(AUTO_BRIGHTNESS_CHECK) == YesNo.NO) {
                    onDeclined()
                    voice.speak("알겠어요. 앞으로는 밝기를 그대로 둘게요.")
                }
            }
        }
    }

    /** 감지 이벤트는 한 번뿐일 수 있다. 다른 도움 대화 중이라고 복구 신호를 버리지 않고 끝나기를 기다린다. */
    private suspend fun whenFree(stillNeeded: () -> Boolean, block: suspend () -> Unit) {
        withTimeoutOrNull(AUTO_RESCUE_WAIT_MS) {
            while (!busy.compareAndSet(false, true)) delay(250)
        } ?: return
        detector.suggestionShowing = true
        try {
            if (stillNeeded()) block()
        } finally {
            detector.suggestionShowing = false
            busy.set(false)
        }
    }

    private fun doneCheck(recipeId: String) = if (recipeId == RecipeIds.BRIGHTEN_SCREEN) BRIGHTNESS_CHECK else DONE_CHECK

    companion object {
        const val BUBBLE_TIMEOUT_MS = 15_000L
        const val LISTEN_TIMEOUT_MS = 5_000L
        const val DONE_CHECK_TIMEOUT_MS = 10_000L
        const val AUTO_RESCUE_WAIT_MS = 60_000L
        val DONE_CHECK = Prompt("이 화면이 맞나요?", yes = "응, 맞아", no = "아니")
        val BRIGHTNESS_CHECK = Prompt("이 밝기 괜찮으세요?", yes = "응, 좋아", no = "아니")
        val AUTO_BRIGHTNESS_CHECK = Prompt("너무 어두워서 밝게 만들었어요.", "어때요?", yes = "좋아", no = "싫어")
        const val UNCHECKED_LOG = "해결됐는지 확인하지 못했어요"
        const val THINKING = "살펴보고 있어요"
        const val SENSITIVE_RECIPE = "sensitive_screen"
        const val SENSITIVE_TITLE = "여기서는 누르지 않을게요"
        const val SENSITIVE_BODY = "아무것도 누르지 말고 뒤로 가기를 누르세요. 가족에게 알릴까요?"
        // 광고인지 아닌지는 어르신 대신 머고가 가린다("이거 안 꺼져"가 화면에 광고가 있으면 광고 넘기기로 간다).
        // 무엇이 문제인지 말로 정리가 안 될 때를 위해 "이거 이상해"를 둔다.
        val HELP_CHOICES = listOf("아까 보던 거", "소리가 안 나", "이거 안 꺼져", "이거 이상해")
        val DISPLAY_RECIPES = setOf(RecipeIds.BRIGHTEN_SCREEN, RecipeIds.INCREASE_TEXT_SIZE)
    }
}
