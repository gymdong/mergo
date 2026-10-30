package app.mergo.ai

import app.mergo.model.BrainContext
import app.mergo.model.KnownApps
import app.mergo.model.RecipeIds

/** core/DiffBuilder가 만드는 diff 항목의 머리말. 예: "영상 제목: ○○ → △△ (터치 없음)" */
object DiffPrefix {
    const val VIDEO = "영상 제목:"
    const val APP = "앱:"
    const val VOLUME = "볼륨:"
    const val NETWORK = "인터넷:"
}

data class KeywordMatch(
    val candidates: List<String>,
    val outOfScope: Boolean,
    val hasTroubleSignal: Boolean,
    /** 소리가 안 난다는데 볼륨은 켜져 있고 줄어들지도 않았다. 볼륨을 되돌려도 달라지는 게 없다. */
    val soundButAudible: Boolean = false,
)

/** 서버 없이 도는 순수 함수 규칙. 후보가 하나면 Brain이 LLM 없이 바로 레시피를 고른다. */
object KeywordRules {
    const val YOUTUBE = "com.google.android.youtube"

    private val OUT_OF_SCOPE = listOf(
        "날씨", "전화", "택시", "돈보내", "돈좀보내", "송금", "계좌", "결제", "비밀번호", "인증번호",
    )
    private val TROUBLE = listOf(
        "안나", "안돼", "안되", "안들", "안꺼", "안닫", "왜", "와이라", "이상", "아까", "이전", "다시",
        "돌아", "뭐고", "어디", "어데", "못", "잘못", "아닌데", "아이다", "없어", "사라",
    )
    private val CLOSE = listOf("꺼져", "안꺼", "안닫", "안없어", "안사라", "못끄", "우째끄", "어떻게끄", "엑스")
    private val SKIP = listOf("건너뛰", "스킵", "넘겨")
    private val SOUND = listOf("소리", "볼륨", "음량", "무음", "안들려", "안들리")
    private val NETWORK = listOf("인터넷", "와이파이", "데이터", "연결")
    private val NOT_SHOWING = listOf("안나와", "안나오", "안돼", "안되")
    private val GONE = listOf("어디갔", "어데갔", "없어졌", "사라졌")
    private val BACK = listOf("아까", "보던", "이전영상", "돌아가", "되돌려", "아닌데", "아이다", "잘못눌")
    private val VAGUE = listOf("뭐고", "와이라", "왜이래", "이상", "잘못됐")
    /**
     * 앱 이름과 함께 나오면 그 앱을 열어 달라는 말로 본다. "유튜브 어디 갔어"처럼 사라진 경우는 [GONE]이 맡는다.
     * "들어가니까 이상해" 같은 불평이 섞이지 않게 요청하는 꼴만 둔다.
     */
    private val OPEN = listOf("어딨", "어디있", "어디에있", "어데있", "열어", "켜줘", "켜주", "켜도", "켜봐", "틀어줘", "틀어주")

    fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    fun match(ctx: BrainContext): KeywordMatch {
        val text = normalize(ctx.utterance.orEmpty())
        val trouble = TROUBLE.any { it in text }
        if (OUT_OF_SCOPE.any { it in text }) return KeywordMatch(emptyList(), true, trouble)

        val videoDiff = ctx.diff.any { it.startsWith(DiffPrefix.VIDEO) }
        val appDiff = ctx.diff.any { it.startsWith(DiffPrefix.APP) }
        val volumeDiff = ctx.diff.any { it.startsWith(DiffPrefix.VOLUME) }
        val found = LinkedHashSet<String>()
        var soundButAudible = false

        fun hasAny(words: List<String>) = words.any { it in text }

        when {
            "광고" in text || hasAny(SKIP) ->
                found += if (ctx.screenHasAd || ctx.current.pkg == YOUTUBE) RecipeIds.SKIP_AD else RecipeIds.SCREEN_AGENT
            hasAny(CLOSE) ->
                found += if (ctx.screenHasAd) RecipeIds.SKIP_AD else RecipeIds.SCREEN_AGENT
        }
        if (hasAny(SOUND)) {
            if (ctx.current.volume == 0 || volumeDiff) found += RecipeIds.SET_VOLUME else soundButAudible = true
        }
        if (hasAny(NETWORK) || (!ctx.current.online && hasAny(NOT_SHOWING))) {
            found += RecipeIds.OPEN_INTERNET_PANEL
        }
        // "유튜브 아까 보던 거 틀어 줘"는 앱 열기가 아니라 영상 되돌리기다. 되돌리기·불평 말이 있으면 앱 열기로 보지 않는다.
        if (found.isEmpty() && !hasAny(BACK) && !hasAny(VAGUE) && openApp(text) != null) found += RecipeIds.OPEN_PREVIOUS_APP
        if (hasAny(GONE)) {
            found += if (videoDiff && !appDiff) RecipeIds.RESTORE_VIDEO else RecipeIds.OPEN_PREVIOUS_APP
        } else if (found.isEmpty() && hasAny(BACK)) {
            when {
                appDiff && !videoDiff -> found += RecipeIds.OPEN_PREVIOUS_APP
                videoDiff && !appDiff -> found += RecipeIds.RESTORE_VIDEO
                "영상" in text || ctx.current.pkg == YOUTUBE -> found += RecipeIds.RESTORE_VIDEO
                else -> found += RecipeIds.OPEN_PREVIOUS_APP
            }
        }
        if (found.isEmpty() && (text.isEmpty() || hasAny(VAGUE))) found += fromDiff(ctx.diff)

        return KeywordMatch(found.toList(), false, trouble, soundButAudible)
    }

    /** 이름으로 열어 달라는 앱. 앱 이름과 [OPEN] 말이 함께 있을 때만. [text]는 [normalize]한 발화. */
    fun openApp(text: String): KnownApps.App? = if (OPEN.any { it in text }) KnownApps.find(text) else null

    private fun fromDiff(diff: List<String>): List<String> = diff.mapNotNull {
        when {
            it.startsWith(DiffPrefix.VIDEO) -> RecipeIds.RESTORE_VIDEO
            it.startsWith(DiffPrefix.APP) -> RecipeIds.OPEN_PREVIOUS_APP
            it.startsWith(DiffPrefix.VOLUME) -> RecipeIds.SET_VOLUME
            it.startsWith(DiffPrefix.NETWORK) -> RecipeIds.OPEN_INTERNET_PANEL
            else -> null
        }
    }.distinct()
}
