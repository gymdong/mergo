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
}
