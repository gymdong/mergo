package app.mergo.detect

import app.mergo.model.RecipeIds

enum class RuleId { R1_AUTOPLAY, R2_HOME_EXIT, R3_MISTOUCH, R4_AD_SKIP, R5_LINK_EXIT }

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
    /** 어르신이 누르는 답은 어르신의 말이라 반말로 쓴다("도와줘" 버튼과 같은 말투). */
    val yesLabel: String = "응, 도와줘",
    val noLabel: String = "아니, 괜찮아",
    /** false면 묻지 않고 조용히 실행한다(광고 건너뛰기). 끝나면 글자 카드로만 알린다. */
    val askFirst: Boolean = true,
) {
    companion object {
        fun restoreVideo(rule: RuleId, title: String, mode: String, now: Long) = Suggestion(
            rule, title, RecipeIds.RESTORE_VIDEO, mapOf("title" to title, "mode" to mode),
            "아까 보시던 영상으로 돌아갈까요?", now, headline = "영상이 바뀐 것 같아요",
            yesLabel = "응, 돌아가 줘", noLabel = "아니, 이거 볼래",
        )
    }
}
