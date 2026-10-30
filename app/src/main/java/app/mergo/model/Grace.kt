package app.mergo.model

/**
 * 영상이 바뀐 뒤 잠깐(30초) 동안만 정해진 말을 듣는 창구. AI 쪽(B)이 오프라인 키워드 인식(Vosk, 문법 제한)으로 구현한다.
 *
 * 영상 소리가 마이크로 들어오므로 자유 받아쓰기(SpeechRecognizer)를 쓰면 안 된다. [GracePhrases]의 문장만 알아듣게 한다.
 * 기기 쪽(A)은 창을 열고 닫기만 하고, 들은 말이 오면 확인 없이 바로 이전 영상으로 되돌린다(어르신이 직접 요청했으므로).
 */
interface GraceListener {
    /** [durationMs] 동안 듣는다. 알아들은 문장을 [onHeard]로 넘긴다. 이미 열려 있으면 새 창으로 바꾼다. */
    fun open(durationMs: Long, onHeard: (String) -> Unit)
    fun close()
}

/** 유예 창에서 알아들을 문장. 호출어(예: "머고야")가 앞에 붙어도 되고 없어도 된다. 사투리 포함. */
object GracePhrases {
    val DEFAULT = listOf(
        "잘못 눌렀다", "잘못 눌렀어", "잘못 눌렀네", "잘못 눌렀노",
        "아까 거", "아까 그거", "아까 보던 거",
        "돌려줘", "되돌려줘", "돌려도", "이거 아닌데", "이거 아이다", "뭐고 이거",
    )

    private fun norm(s: String) = s.replace(Regex("\\s+"), "")

    fun matches(heard: String, phrases: List<String> = DEFAULT): Boolean {
        val h = norm(heard)
        return phrases.any { h.contains(norm(it)) }
    }
}
