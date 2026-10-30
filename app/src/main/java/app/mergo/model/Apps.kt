package app.mergo.model

/**
 * 이름으로 열 수 있는 앱. 레시피 open_previous_app의 params["app"]에 [App.id]를 넣는다.
 * 여기 없는 앱(스토어·설정·금융 앱 등)은 이름으로 열지 않는다. LLM이 패키지를 직접 고르지 못하게 하려는 허용 목록이다.
 */
object KnownApps {
    /** [obj]는 목적격 조사까지 붙인 이름("유튜브를"). [words]는 띄어쓰기를 뺀 발화에서 찾을 말. 기기마다 패키지가 달라 [pkgs]는 앞에서부터 시도한다. */
    data class App(val id: String, val obj: String, val words: List<String>, val pkgs: List<String>)

    val ALL = listOf(
        App("youtube", "유튜브를", listOf("유튜브", "유투브", "유튭"), listOf("com.google.android.youtube")),
        App("kakaotalk", "카카오톡을", listOf("카톡", "카카오톡"), listOf("com.kakao.talk")),
        App(
            "gallery", "사진첩을", listOf("사진첩", "갤러리", "앨범"),
            listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
        ),
        App(
            "camera", "카메라를", listOf("카메라"),
            listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera2"),
        ),
    )

    fun byId(id: String?): App? = ALL.firstOrNull { it.id == id }

    /** 띄어쓰기를 뺀 발화에서 처음 나오는 앱. */
    fun find(normalized: String): App? = ALL.firstOrNull { app -> app.words.any { it in normalized } }
}
