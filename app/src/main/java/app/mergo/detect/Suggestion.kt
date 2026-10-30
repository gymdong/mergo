package app.mergo.detect

import app.mergo.model.RecipeIds

enum class RuleId { R1_AUTOPLAY, R2_HOME_EXIT, R3_MISTOUCH, R4_AD_SKIP }

/** 감지 규칙이 어르신께 드리는 제안 하나. */
data class Suggestion(
    val rule: RuleId,
    val target: String,              // 쿨다운 기준 대상 (영상 제목, 앱 패키지 등)
    val recipeId: String,
    val params: Map<String, String>,
    val say: String,
    val createdAt: Long,
    /** 말풍선의 큰 제목. 예: "영상이 바뀐 것 같아요" */
    val headline: String = "도와드릴까요?",
    val yesLabel: String = "예, 도와주세요",
    val noLabel: String = "아니요, 괜찮아요",
) {
    companion object {
        fun restoreVideo(rule: RuleId, title: String, mode: String, now: Long) = Suggestion(
            rule, title, RecipeIds.RESTORE_VIDEO, mapOf("title" to title, "mode" to mode),
            "아까 보시던 영상으로 돌아갈까요?", now, headline = "영상이 바뀐 것 같아요",
            yesLabel = "예, 돌아가 주세요", noLabel = "아니요, 지금 볼게요",
        )
    }
}
