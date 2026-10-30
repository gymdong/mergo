package com.mergo.model

// B가 구현, A의 레시피 체인 마지막 단계에서 호출
interface ScreenAgent {
    suspend fun achieve(goal: String): Boolean
}
