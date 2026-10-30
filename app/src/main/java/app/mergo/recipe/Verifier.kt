package app.mergo.recipe

import kotlinx.coroutines.delay

/** 실행 후 화면·기기 상태를 다시 읽어 원래 상태가 됐는지 확인한다. */
object Verifier {
    suspend fun waitUntil(timeoutMs: Long = 5_000, pollMs: Long = 300, check: () -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            val ok = try { check() } catch (e: RuntimeException) { false }
            if (ok) return true
            if (waited >= timeoutMs) return false
            delay(pollMs)
            waited += pollMs
        }
    }

    /**
     * [waitUntil]과 같지만 [check]가 null(상태를 읽지 못함)을 돌려줄 수 있다.
     * 끝까지 true가 없을 때, 한 번이라도 읽었으면 false, 한 번도 못 읽었으면 null. 읽다가 난 예외도 못 읽은 것으로 본다.
     */
    suspend fun waitFor(timeoutMs: Long = 5_000, pollMs: Long = 300, check: () -> Boolean?): Boolean? {
        var waited = 0L
        var read = false
        while (true) {
            val ok = try { check() } catch (e: RuntimeException) { null }
            if (ok == true) return true
            if (ok != null) read = true
            if (waited >= timeoutMs) return if (read) false else null
            delay(pollMs)
            waited += pollMs
        }
    }
}
