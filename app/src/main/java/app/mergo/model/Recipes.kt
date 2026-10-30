package app.mergo.model

/** 정해진 순서로 대신 조작하는 복구 방법. 되돌릴 수 있는 행동만 한다. */
interface Recipe {
    val id: String
    suspend fun run(params: Map<String, String>): RecipeOutcome
}

/**
 * 복구 결과. 해결로 세는 것은 [Verified]뿐이다. 분류 기준은 docs/persona-scenarios.md §4.
 * LLM의 '완료', 클릭 성공 여부, 사용자의 "네"는 독립 관측이 아니다.
 */
sealed interface RecipeOutcome {
    /** 행동 뒤 목표 상태를 다시 읽어 확인했다. [acted]=false면 이미 목표 상태라 아무것도 누르지 않았다. */
    data class Verified(val acted: Boolean = true) : RecipeOutcome

    /** 일부러 조작하지 않고 안내로 끝냈다. 이것이 정해진 결말이다. 예: 건너뛸 수 없는 광고. */
    data class Guided(val said: String) : RecipeOutcome

    /** 행동은 했지만 목표 상태를 읽지 못했다(검증 조건이 없거나 관측값이 null). '읽었더니 달랐다'는 [Failed]다. */
    data class Unverifiable(val why: String) : RecipeOutcome

    data class Failed(val reason: FailReason) : RecipeOutcome
}

enum class FailReason {
    PRECONDITION,   // 돌아갈 대상(정상 상태 제목·앱)이 없어 시작하지 않음
    NOT_REACHED,    // 시도했지만 다시 읽은 상태가 목표와 다름, 또는 어떤 방법도 실행하지 못함
    AGENT_GAVE_UP,  // 화면 에이전트가 실패를 돌려줌(안전 중단·단계 초과·LLM 포기를 아직 구분하지 않음)
    NO_RECIPE,      // 고른 레시피가 등록돼 있지 않음
    ERROR,          // 레시피 실행 중 예외
}

object RecipeIds {
    const val RESTORE_VIDEO = "restore_video"
    const val SET_VOLUME = "set_volume"
    const val OPEN_PREVIOUS_APP = "open_previous_app"
    const val OPEN_INTERNET_PANEL = "open_internet_panel"
    const val SKIP_AD = "skip_ad"
    const val SCREEN_AGENT = "screen_agent"
    const val BRIGHTEN_SCREEN = "brighten_screen"
    const val INCREASE_TEXT_SIZE = "increase_text_size"
    const val ASK = "ask"

    val ALL = listOf(RESTORE_VIDEO, SET_VOLUME, OPEN_PREVIOUS_APP, OPEN_INTERNET_PANEL, SKIP_AD, SCREEN_AGENT, BRIGHTEN_SCREEN, INCREASE_TEXT_SIZE)
}
