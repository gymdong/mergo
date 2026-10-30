package app.mergo.core

import app.mergo.model.RecipeIds

/** 복구 중·완료 안내와 가족 리포트에 남길 문구. 시안의 말투(존댓말, 짧게)를 따른다. */
data class Texts(val progress: String, val done: String, val log: String, val failLog: String) {
    companion object {
        fun of(recipeId: String): Texts = when (recipeId) {
            RecipeIds.RESTORE_VIDEO -> Texts("아까 보던 영상으로 돌아가고 있어요", "원래 영상으로 돌아왔어요", "이전 영상을 다시 찾았어요", "이전 영상을 찾지 못했어요")
            RecipeIds.SET_VOLUME -> Texts("소리를 다시 켜고 있어요", "소리를 다시 켰어요", "소리를 다시 켰어요", "소리를 켜지 못했어요")
            RecipeIds.OPEN_PREVIOUS_APP -> Texts("보던 앱으로 돌아가고 있어요", "보던 앱으로 돌아왔어요", "보던 앱으로 돌아갔어요", "보던 앱을 열지 못했어요")
            RecipeIds.SKIP_AD -> Texts("광고를 넘기고 있어요", "광고를 넘겼어요", "광고를 넘겼어요", "광고를 넘기지 못했어요")
            RecipeIds.OPEN_INTERNET_PANEL -> Texts("인터넷을 다시 켜고 있어요", "인터넷을 다시 켰어요", "인터넷을 다시 켰어요", "인터넷을 켜지 못했어요")
            RecipeIds.SCREEN_AGENT -> Texts("화면을 살펴보고 있어요", "다 됐어요", "화면 문제를 해결했어요", "화면 문제를 해결하지 못했어요")
            else -> Texts("도와드리고 있어요", "다 됐어요", "도와드렸어요", "해결하지 못했어요")
        }
    }
}
