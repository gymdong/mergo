package com.mergo.model

// B가 구현, A의 OverlayUi/Orchestrator가 호출
enum class YesNo { YES, NO, NONE }

interface VoiceIO {
    suspend fun speak(text: String)
    suspend fun listen(timeoutMs: Long = 5000): String?
    suspend fun listenYesNo(timeoutMs: Long = 5000): YesNo
}
