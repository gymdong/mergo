package app.mergo.model

enum class YesNo { YES, NO, NONE }

interface VoiceIO {
    suspend fun speak(text: String)
    /** 자유 발화를 받는다. 못 알아들으면 null. */
    suspend fun listen(timeoutMs: Long = 5000): String?
    /** 긍정어/부정어만 판정한다. */
    suspend fun listenYesNo(timeoutMs: Long = 5000): YesNo
    /** 진행 중인 듣기를 멈춘다. 말풍선 버튼이 먼저 눌렸을 때 쓴다. */
    fun cancelListening() {}
}
