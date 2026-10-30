package app.mergo.ai

import app.mergo.model.YesNo

object YesNoParser {
    private val NO = listOf("아니", "됐어", "싫어")
    private val YES = listOf("그래", "맞아", "좋아")
    private val YES_EXACT = setOf("응", "네", "예", "어")

    /** 부정어를 먼저 본다. "됐어"에 긍정어 "어"가 들어 있어서 짧은 긍정어는 말 전체가 같을 때만 인정한다. */
    fun parse(text: String?): YesNo {
        val said = KeywordRules.normalize(text.orEmpty())
        return when {
            said.isEmpty() -> YesNo.NONE
            NO.any { it in said } -> YesNo.NO
            YES.any { it in said } -> YesNo.YES
            said in YES_EXACT -> YesNo.YES
            else -> YesNo.NONE
        }
    }
}
