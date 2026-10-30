package com.mergo.model

// A가 구현, B의 Brain이 id로 고름
// id: restore_video, set_volume, open_previous_app, open_internet_panel, skip_ad, screen_agent, ask
interface Recipe {
    val id: String
    suspend fun run(params: Map<String, String>): Boolean
}
