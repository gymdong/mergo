package app.mergo.ai

import app.mergo.model.Brain
import app.mergo.model.BrainContext
import app.mergo.model.BrainDecision
import app.mergo.model.RecipeIds
import app.mergo.model.VoiceIO
import app.mergo.model.YesNo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 키워드 규칙을 먼저 쓰고, 후보가 하나로 정해지지 않을 때만 LLM을 부른다. */
class MergoBrain(
    private val llm: LlmClient,
    private val voice: VoiceIO,
    private val autoExecute: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) : Brain {

    override suspend fun decide(ctx: BrainContext): BrainDecision {
        val match = KeywordRules.match(ctx)
        log("brain keyword candidates=${match.candidates} outOfScope=${match.outOfScope}")
        if (match.outOfScope) return outOfScope()
        match.candidates.singleOrNull()?.let { return decisionFor(it, ctx) }

        val fromLlm = askLlm(ctx)?.let { guard(it, ctx, match) }?.let { fitToScreen(it, ctx) }
        log("brain llm decision=$fromLlm")
        if (fromLlm != null && fromLlm.recipe != RecipeIds.ASK) {
            return fromLlm.copy(confirm = !autoExecute())
        }
        if (match.candidates.isNotEmpty()) return askCandidates(match.candidates, ctx)
        return fromLlm ?: fallbackAsk()
    }

    private suspend fun askLlm(ctx: BrainContext): BrainDecision? = coroutineScope {
        val notice = launch {
            delay(NOTICE_AFTER_MS)
            voice.speak("잠시만요")
        }
        try {
            withTimeoutOrNull(LlmHttp.TIMEOUT_MS) {
                llm.complete(LlmPrompt.SYSTEM, LlmPrompt.request(ctx))
            }?.let(LlmJson::parseDecision)
        } catch (e: LlmException) {
            log("brain llm failed: ${e.message}")
            null
        } finally {
            notice.cancel()
        }
    }

    /**
     * 막혔다는 근거가 없으면 LLM 답을 쓰지 않는다. 시험에서 범위 밖 발화에 레시피를 고르거나,
     * ask로 답하면서 "어떤 노래를 들려드릴까요?"처럼 할 수 없는 일을 약속한 적이 있다.
     */
    private fun guard(decision: BrainDecision, ctx: BrainContext, match: KeywordMatch): BrainDecision {
        val noEvidence = match.candidates.isEmpty() && ctx.diff.isEmpty() && !match.hasTroubleSignal
        return if (noEvidence) outOfScope() else decision
    }

    /** 건너뛰기는 YouTube 광고 전용이다. 다른 앱의 광고는 화면을 보고 닫아야 한다. */
    private fun fitToScreen(decision: BrainDecision, ctx: BrainContext): BrainDecision {
        val otherApp = ctx.current.pkg != KeywordRules.YOUTUBE && !ctx.screenHasAd
        if (decision.recipe != RecipeIds.SKIP_AD || !otherApp) return decision
        return decision.copy(recipe = RecipeIds.SCREEN_AGENT, params = mapOf("goal" to "광고 닫기"))
    }

    /** F9: 가능성이 높은 후보부터 예/아니오로 최대 2번 묻는다. */
    private suspend fun askCandidates(candidates: List<String>, ctx: BrainContext): BrainDecision {
        for (recipe in candidates.take(MAX_QUESTIONS)) {
            voice.speak(SAY.getValue(recipe).question)
            if (voice.listenYesNo() == YesNo.YES) {
                return decisionFor(recipe, ctx).copy(confirm = false, say = SAY.getValue(recipe).statement)
            }
        }
        return fallbackAsk()
    }

    private fun decisionFor(recipe: String, ctx: BrainContext): BrainDecision {
        val auto = autoExecute()
        val say = SAY.getValue(recipe)
        return BrainDecision(
            diagnosis = "키워드 규칙",
            recipe = recipe,
            params = paramsFor(recipe, ctx),
            say = if (auto) say.statement else say.question,
            confirm = !auto,
        )
    }

    private fun paramsFor(recipe: String, ctx: BrainContext): Map<String, String> = when (recipe) {
        RecipeIds.SCREEN_AGENT -> mapOf("goal" to "광고 닫기")
        RecipeIds.RESTORE_VIDEO -> previousTitle(ctx)?.let { mapOf("title" to it) }.orEmpty()
        else -> emptyMap()
    }

    private fun previousTitle(ctx: BrainContext): String? = ctx.diff
        .firstOrNull { it.startsWith(DiffPrefix.VIDEO) }
        ?.removePrefix(DiffPrefix.VIDEO)
        ?.substringBefore("→")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun outOfScope() = BrainDecision(
        diagnosis = "범위 밖 발화",
        recipe = RecipeIds.ASK,
        params = emptyMap(),
        say = "그건 제가 해 드리기 어려워요. 화면에 문제가 있으신가요?",
        confirm = false,
    )

    private fun fallbackAsk() = BrainDecision(
        diagnosis = "판단 불가",
        recipe = RecipeIds.ASK,
        params = emptyMap(),
        say = "제가 잘 못 알아들었어요. 화면에 문제가 있으신가요?",
        confirm = false,
    )

    private data class Say(val question: String, val statement: String)

    private companion object {
        const val NOTICE_AFTER_MS = 1_500L
        const val MAX_QUESTIONS = 2

        val SAY = mapOf(
            RecipeIds.RESTORE_VIDEO to Say("아까 보시던 영상으로 돌아갈까요?", "아까 보시던 영상으로 돌아갈게요."),
            RecipeIds.SET_VOLUME to Say("소리를 다시 켜 드릴까요?", "소리를 다시 켜 드릴게요."),
            RecipeIds.OPEN_PREVIOUS_APP to Say("아까 보시던 앱으로 돌아갈까요?", "아까 보시던 앱으로 돌아갈게요."),
            RecipeIds.OPEN_INTERNET_PANEL to Say("인터넷을 다시 연결해 볼까요?", "인터넷을 다시 연결해 볼게요."),
            RecipeIds.SKIP_AD to Say("광고 넘길까요?", "광고를 넘길게요."),
            RecipeIds.SCREEN_AGENT to Say("광고를 닫아 드릴까요?", "광고를 닫아 드릴게요."),
        )
    }
}
