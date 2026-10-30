package com.mergo.model

// B가 구현, A의 Orchestrator가 호출
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
