package app.mergo.core.fakes

import android.util.Log
import app.mergo.model.Brain
import app.mergo.model.BrainContext
import app.mergo.model.BrainDecision
import app.mergo.model.RecipeIds
import app.mergo.model.ScreenAgent
import app.mergo.model.SettingsSource
import app.mergo.model.VoiceIO
import app.mergo.model.YesNo

/*
 * AI 쪽(B)이 진짜 구현을 넘겨주기 전까지 기기 쪽을 끝까지 돌려 보기 위한 가짜 구현.
 * B의 구현이 오면 MergoService의 조립 코드에서 교체한다.
 */

/** 도와줘 선택지 4개만 정확히 알아듣는 Brain. 그 밖의 말은 되묻는다. */
class ChoiceBrain : Brain {
    override suspend fun decide(ctx: BrainContext): BrainDecision {
        val u = ctx.utterance?.trim().orEmpty()
        return when {
            u.contains("광고") || ctx.screenHasAd && u.contains("안 꺼져") ->
                BrainDecision("광고가 재생 중", RecipeIds.SKIP_AD, emptyMap(), "광고를 넘겨 드릴까요?", true)
            u.contains("아까") ->
                if (ctx.current.pkg == "com.google.android.youtube") {
                    BrainDecision("이전 영상으로", RecipeIds.RESTORE_VIDEO, emptyMap(), "아까 보시던 영상으로 돌아갈까요?", true)
                } else {
                    BrainDecision("직전 앱으로", RecipeIds.OPEN_PREVIOUS_APP, emptyMap(), "아까 보시던 화면으로 돌아갈까요?", true)
                }
            u.contains("소리") ->
                BrainDecision("소리가 꺼짐", RecipeIds.SET_VOLUME, emptyMap(), "소리를 다시 켤까요?", true)
            u.contains("안 꺼져") ->
                BrainDecision("창이 안 닫힘", RecipeIds.SCREEN_AGENT, mapOf("goal" to "광고 닫기"), "광고를 닫아 드릴까요?", true)
            else -> BrainDecision("잘 모르겠음", RecipeIds.ASK, emptyMap(), "잘 못 알아들었어요. 화면의 버튼을 눌러 주세요.", false)
        }
    }
}

/** 화면 버튼만 쓰는 VoiceIO. 말은 로그로만 남기고 듣기는 하지 않는다. */
class SilentVoice : VoiceIO {
    override suspend fun speak(text: String) {
        runCatching { Log.i("Mergo", "말하기: $text") }
    }
    override suspend fun listen(timeoutMs: Long): String? = null
    override suspend fun listenYesNo(timeoutMs: Long): YesNo = YesNo.NONE
}

/** 아직 화면 조작 에이전트가 없을 때. 항상 실패를 돌려 레시피 체인이 가족 알림으로 넘어가게 한다. */
class NoAgent : ScreenAgent {
    override suspend fun achieve(goal: String): Boolean = false
}

/** AI 쪽 유예 듣기(Vosk)가 오기 전까지. 아무것도 듣지 않는다. */
class NoGraceListener : app.mergo.model.GraceListener {
    override fun open(durationMs: Long, onHeard: (String) -> Unit) {
        runCatching { Log.i("Mergo", "유예 듣기(가짜) ${durationMs}ms — B의 Vosk 구현으로 교체") }
    }
    override fun close() {}
}

class DefaultSettings(
    override val autoExecute: Boolean = false,
    override val wakeWord: String = "머고야",
) : SettingsSource
