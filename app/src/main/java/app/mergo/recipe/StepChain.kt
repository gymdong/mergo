package app.mergo.recipe

import android.util.Log
import kotlinx.coroutines.CancellationException

/** 복구 방법 하나. [act]가 실행되고 [verify]가 통과해야 성공이다. 실패하면 [cleanup]으로 연 화면을 닫는다. */
class Step(
    val name: String,
    val act: suspend () -> Boolean,
    val verify: suspend () -> Boolean,
    val cleanup: suspend () -> Unit = {},
)

/** 앞 방법이 실패했을 때만 다음 방법으로 넘어간다. */
object StepChain {
    var log: (String) -> Unit = { runCatching { Log.i("Mergo", it) } }

    suspend fun run(recipeId: String, steps: List<Step>): Boolean {
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
            val ok = acted && s.verify()
            log("$recipeId/${s.name} acted=$acted verified=$ok ${System.currentTimeMillis() - started}ms")
            if (ok) return true
            if (acted) s.cleanup()
        }
        return false
    }
}
