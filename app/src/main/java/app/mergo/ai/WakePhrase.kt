package app.mergo.ai

/** 호출어를 찾았다. [command]는 호출어 뒤에 이어 말한 내용이고, 호출어만 불렀으면 null이다. */
data class WakeHit(val command: String?)

/** 받아쓴 문장에서 호출어를 찾아 뒤에 이어진 말을 꺼낸다. 띄어쓰기 없이 받아써져도 찾는다. */
object WakePhrase {
    private const val DEFAULT_WAKE_WORD = "머고야"

    private val DEFAULT_NAMES = listOf("머고야", "뭐고야")

    /**
     * 받아쓰기가 호출어를 바꿔 쓴 말이다. 일상어이기도 해서 말의 맨 처음일 때만 인정한다.
     * "많이 먹어야 힘이 나지"처럼 앞에 다른 말이 있으면 부른 것이 아니다.
     */
    private val SOUND_ALIKES = listOf("먹어야", "머거야", "머구야", "모고야")

    /** 호출어 앞에 "어", "저기" 같은 군소리가 붙는 것까지 허용한다. 긴 문장 중간에 나온 말은 부른 것으로 보지 않는다. */
    private const val MAX_START = 3

    fun find(text: String, wakeWord: String): WakeHit? {
        val said = KeywordRules.normalize(text)
        val word = KeywordRules.normalize(wakeWord)
        val names = if (word == DEFAULT_WAKE_WORD) DEFAULT_NAMES else listOf(word)
        val sounds = if (word == DEFAULT_WAKE_WORD) SOUND_ALIKES else emptyList()
        val end = names.mapNotNull { name -> said.indexOf(name).takeIf { it in 0..MAX_START }?.plus(name.length) }
            .plus(sounds.filter { said.startsWith(it) }.map { it.length })
            .minOrNull() ?: return null
        return WakeHit(said.substring(end).ifEmpty { null })
    }
}
