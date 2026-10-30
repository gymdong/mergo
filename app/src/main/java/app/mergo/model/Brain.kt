package app.mergo.model

/** Brain에 넘기는 상황. utterance가 null이면 감지 규칙이 부른 경우다. */
data class BrainContext(
    val utterance: String?,
    val diff: List<String>,
    val timeline: List<String>,
    val current: Baseline,
    val screenHasAd: Boolean,
)

data class BrainDecision(
    val diagnosis: String,
    val recipe: String,
    val params: Map<String, String>,
    val say: String,
    val confirm: Boolean,
)

interface Brain {
    suspend fun decide(ctx: BrainContext): BrainDecision
}
