package app.mergo.recipe

import app.mergo.model.FailReason
import app.mergo.model.RecipeOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepChainTest {
    private fun step(name: String, acted: Boolean, verified: Boolean?) = Step(name, { acted }, { verified })

    @Test
    fun `앞 방법은 못 읽었어도 뒤 방법이 읽어서 다르면 실패`() = runTest {
        val outcome = StepChain.run("t", listOf(step("a", true, null), step("b", true, false)))
        assertEquals(RecipeOutcome.Failed(FailReason.NOT_REACHED), outcome)
    }

    @Test
    fun `마지막으로 실행한 방법의 결과를 못 읽으면 검증 불가`() = runTest {
        val outcome = StepChain.run("t", listOf(step("a", true, false), step("b", true, null), step("c", false, false)))
        assertTrue(outcome is RecipeOutcome.Unverifiable)
    }

    @Test
    fun `아무 방법도 실행하지 못하면 실패`() = runTest {
        assertEquals(RecipeOutcome.Failed(FailReason.NOT_REACHED), StepChain.run("t", listOf(step("a", false, null))))
    }

    @Test
    fun `읽다가 예외가 나면 못 읽은 것으로 본다`() = runTest {
        assertEquals(null, Verifier.waitFor(600) { throw IllegalStateException("창 없음") })
    }

    @Test
    fun `한 번이라도 읽었으면 끝까지 달라도 false`() = runTest {
        var n = 0
        assertEquals(false, Verifier.waitFor(600) { if (n++ == 0) false else null })
    }
}
