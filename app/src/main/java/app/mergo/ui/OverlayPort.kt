package app.mergo.ui

import app.mergo.model.YesNo

/** 하단 시트 질문 하나. 예: 작은 안내 "머고가 알아챘어요", 제목 "영상이 바뀐 것 같아요", 본문 "아까 보시던 영상으로 돌아갈까요?" */
data class Prompt(
    val title: String,
    val body: String? = null,
    val eyebrow: String? = null,
    val yes: String = "예",
    val no: String = "아니요",
)

/** 화면 위 말풍선·버튼. 접근성 오버레이가 구현하고, 테스트에서는 가짜로 바꾼다. */
interface OverlayPort {
    /** 큰 [예]/[아니요] 하단 시트. 시간 안에 누르지 않으면 NONE. */
    suspend fun ask(prompt: Prompt, timeoutMs: Long): YesNo
    /** 도와줘를 눌렀을 때의 선택지 화면. 고른 글자를 돌려주고, 시간 안에 안 고르면 null. */
    suspend fun choose(prompt: String, choices: List<String>, timeoutMs: Long): String?
    /** 복구 중 안내 카드. null이면 숨긴다. */
    fun progress(text: String?)
    /** 복구 완료 안내 카드. 몇 초 뒤 스스로 사라진다. */
    fun done(title: String, body: String)
    fun dismiss()
}
