package app.mergo.model

/** 정해진 순서로 대신 조작하는 복구 방법. 되돌릴 수 있는 행동만 한다. */
interface Recipe {
    val id: String
    suspend fun run(params: Map<String, String>): Boolean
}

object RecipeIds {
    const val RESTORE_VIDEO = "restore_video"
    const val SET_VOLUME = "set_volume"
    const val OPEN_PREVIOUS_APP = "open_previous_app"
    const val OPEN_INTERNET_PANEL = "open_internet_panel"
    const val SKIP_AD = "skip_ad"
    const val SCREEN_AGENT = "screen_agent"
    const val ASK = "ask"

    val ALL = listOf(RESTORE_VIDEO, SET_VOLUME, OPEN_PREVIOUS_APP, OPEN_INTERNET_PANEL, SKIP_AD, SCREEN_AGENT)
}
