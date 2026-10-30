package app.mergo.recipe

import android.util.Log
import app.mergo.model.FailReason
import app.mergo.model.RecipeOutcome
import kotlinx.coroutines.CancellationException

/**
 * 복구 방법 하나. [act]가 실행되고 [verify]가 true여야 성공이다. 실패하면 [cleanup]으로 연 화면을 닫는다.
 * [verify]의 null은 '상태를 읽지 못함'이다(예: 영상 제목을 얻지 못함).
 */
class Step(
    val name: String,
    val act: suspend () -> Boolean,
    val verify: suspend () -> Boolean?,
    val cleanup: suspend () -> Unit = {},
)

/** 앞 방법이 실패했을 때만 다음 방법으로 넘어간다. */
object StepChain {
    var log: (String) -> Unit = { runCatching { Log.i("Mergo", it) } }

    /**
     * 한 방법이라도 검증되면 [RecipeOutcome.Verified]. 아니면 마지막으로 실행한 방법의 확인 결과를 따른다.
     * 그 결과를 읽지 못했으면 검증 불가, 읽었는데 달랐거나 아무 방법도 실행하지 못했으면 실패다.
     */
    suspend fun run(recipeId: String, steps: List<Step>): RecipeOutcome {
        var unread: String? = null
        for (s in steps) {
            val started = System.currentTimeMillis()
            val acted = try {
                s.act()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("$recipeId/${s.name} 오류: ${e.message}")
                false
            }
            val verified = if (acted) s.verify() else false
            log("$recipeId/${s.name} acted=$acted verified=$verified ${System.currentTimeMillis() - started}ms")
            if (verified == true) return RecipeOutcome.Verified()
            if (acted) {
                unread = if (verified == null) "${s.name} 뒤 상태를 읽지 못함" else null
                s.cleanup()
            }
        }
        return unread?.let { RecipeOutcome.Unverifiable(it) } ?: RecipeOutcome.Failed(FailReason.NOT_REACHED)
    }
}
