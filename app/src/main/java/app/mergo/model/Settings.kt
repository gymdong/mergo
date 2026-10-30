package app.mergo.model

/** 가족이 설정 화면에서 바꾸는 값. 설정 화면은 AI 쪽이 만들고, 기기 쪽은 읽기만 한다. */
interface SettingsSource {
    /** true면 제안·도와줘 모두 확인 없이 바로 실행한다. 위험 노드에서는 설정과 관계없이 멈춘다. */
    val autoExecute: Boolean
    val wakeWord: String
}
