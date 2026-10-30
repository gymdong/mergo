package app.mergo.recipe

import app.mergo.model.FailReason
import app.mergo.model.RecipeIds
import app.mergo.model.RecipeOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingsTest {
    @Test
    fun `밝기가 50 밑이면 자동 밝기여도 너무 어둡다고 본다`() {
        assertTrue(AutoBrightness.isTooDark(DisplaySnapshot(49, false, 1f)))
        assertTrue(AutoBrightness.isTooDark(DisplaySnapshot(10, true, 1f)))
        assertFalse(AutoBrightness.isTooDark(DisplaySnapshot(50, true, 1f)))
        assertFalse(AutoBrightness.isTooDark(DisplaySnapshot(null, false, 1f)))
    }

    @Test
    fun `정해진 밝기를 받으면 그 값으로 맞춘다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(30, false, 1f))
        val level = mapOf("level" to AutoBrightness.COMFORT.toString())
        assertEquals(RecipeOutcome.Verified(), BrightenScreen(display) { 170 }.run(level))
        assertEquals(AutoBrightness.COMFORT, display.state.brightness)
    }

    @Test
    fun `자동 밝기 때문에 어두워졌으면 밝게 하면서 자동 밝기를 끈다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(30, true, 1f))
        val level = mapOf("level" to AutoBrightness.COMFORT.toString())
        assertEquals(RecipeOutcome.Verified(), BrightenScreen(display) { null }.run(level))
        assertEquals(false, display.state.automaticBrightness)
    }

    private class FakeDisplay(
        var state: DisplaySnapshot,
        var allowed: Boolean = true,
        var writeWorks: Boolean = true,
    ) : DisplaySettingsPort {
        var brightnessWrites = 0
        var fontWrites = 0
        override fun canWrite() = allowed
        override fun read() = state
        override fun setBrightness(value: Int): Boolean {
            brightnessWrites++
            if (writeWorks) state = state.copy(brightness = value, automaticBrightness = false)
            return writeWorks
        }
        override fun setFontScale(value: Float): Boolean {
            fontWrites++
            if (writeWorks) state = state.copy(fontScale = value)
            return writeWorks
        }
    }

    @Test
    fun `도와줘 밝게 해줘는 저장값 또는 안전한 단계만큼 직접 올리고 다시 읽어 확인`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(20, true, 1f))
        assertEquals(RecipeOutcome.Verified(), BrightenScreen(display) { 170 }.run(emptyMap()))
        assertEquals(170, display.state.brightness)
        assertEquals(false, display.state.automaticBrightness)
        assertEquals(1, display.brightnessWrites)
    }

    @Test
    fun `글씨가 작아는 저장값이 없어도 한 단계 올리되 상한을 넘지 않는다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(128, false, 1f))
        assertEquals(RecipeOutcome.Verified(), IncreaseTextSize(display) { null }.run(emptyMap()))
        assertTrue(display.state.fontScale!! >= 1.2f)
        assertEquals(1, display.fontWrites)
    }

    @Test
    fun `이미 밝은 화면에서도 더 밝게 해달라면 255까지 올릴 수 있다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(240, false, 1f))
        assertEquals(RecipeOutcome.Verified(), BrightenScreen(display) { null }.run(emptyMap()))
        assertEquals(255, display.state.brightness)
    }

    @Test
    fun `자동 밝기 중에는 저장 밝기값이 최대여도 어둡다는 요청으로 수동 모드로 바꾼다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(255, true, 1f))
        assertEquals(RecipeOutcome.Verified(), BrightenScreen(display) { null }.run(emptyMap()))
        assertEquals(255, display.state.brightness)
        assertEquals(false, display.state.automaticBrightness)
        assertEquals(1, display.brightnessWrites)
    }

    @Test
    fun `수동 밝기가 이미 최대이면 불필요한 변경을 하지 않는다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(255, false, 1f))
        assertTrue(BrightenScreen(display) { null }.run(emptyMap()) is RecipeOutcome.Guided)
        assertEquals(0, display.brightnessWrites)
    }

    @Test
    fun `권한 없으면 두 설정 모두 변경하지 않고 안내한다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(10, false, 0.8f), allowed = false)
        assertTrue(BrightenScreen(display) { 150 }.run(emptyMap()) is RecipeOutcome.Guided)
        assertTrue(IncreaseTextSize(display) { 1.4f }.run(emptyMap()) is RecipeOutcome.Guided)
        assertEquals(0, display.brightnessWrites + display.fontWrites)
    }

    @Test
    fun `자동 복구는 저장한 값으로만 되돌리고 시작 시 낮은 값은 추정하지 않는다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(30, false, 0.8f))
        assertEquals(RecipeOutcome.Verified(), BrightenScreen(display) { 160 }.run(mapOf("restore" to "true")))
        assertEquals(160, display.state.brightness)
        assertEquals(RecipeOutcome.Verified(), IncreaseTextSize(display) { 1.3f }.run(mapOf("restore" to "true")))
        assertEquals(1.3f, display.state.fontScale)
        assertEquals(RecipeOutcome.Failed(FailReason.PRECONDITION),
            BrightenScreen(display) { null }.run(mapOf("restore" to "true")))
    }

    @Test
    fun `설정 쓰기 실패는 해결로 보고하지 않는다`() = runTest {
        val display = FakeDisplay(DisplaySnapshot(20, false, 1f), writeWorks = false)
        assertEquals(RecipeOutcome.Failed(FailReason.ERROR), BrightenScreen(display) { 160 }.run(emptyMap()))
        assertEquals(RecipeOutcome.Failed(FailReason.ERROR), IncreaseTextSize(display) { 1.3f }.run(emptyMap()))
    }

    @Test
    fun `급격한 수동 설정 하락만 자동 복구 후보가 된다`() {
        val monitor = DisplayRescueMonitor()
        assertTrue(monitor.onSnapshot(DisplaySnapshot(160, false, 1.4f), true, 160, 1.4f).isEmpty())
        assertEquals(setOf(RecipeIds.BRIGHTEN_SCREEN, RecipeIds.INCREASE_TEXT_SIZE),
            monitor.onSnapshot(DisplaySnapshot(40, false, 1f), true, 160, 1.4f))
        assertTrue(monitor.onSnapshot(DisplaySnapshot(40, false, 1f), true, 160, 1.4f).isEmpty())
    }

    @Test
    fun `자동 밝기와 미동의 또는 작은 변화는 자동 복구하지 않는다`() {
        val auto = DisplayRescueMonitor()
        auto.onSnapshot(DisplaySnapshot(160, true, 1.3f), true, 160, 1.3f)
        assertFalse(RecipeIds.BRIGHTEN_SCREEN in auto.onSnapshot(DisplaySnapshot(30, true, 1.3f), true, 160, 1.3f))
        val off = DisplayRescueMonitor()
        off.onSnapshot(DisplaySnapshot(160, false, 1.3f), false, 160, 1.3f)
        assertTrue(off.onSnapshot(DisplaySnapshot(30, false, 1f), false, 160, 1.3f).isEmpty())
        val small = DisplayRescueMonitor()
        small.onSnapshot(DisplaySnapshot(160, false, 1.3f), true, 160, 1.3f)
        assertTrue(small.onSnapshot(DisplaySnapshot(130, false, 1.2f), true, 160, 1.3f).isEmpty())
    }

    @Test
    fun `슬라이더를 여러 단계로 내린 급격한 변화도 저장 기준에서 복구한다`() {
        val monitor = DisplayRescueMonitor()
        assertTrue(monitor.onSnapshot(DisplaySnapshot(160, false, 1.4f), true, 160, 1.4f, 0).isEmpty())
        assertTrue(monitor.onSnapshot(DisplaySnapshot(110, false, 1.3f), true, 160, 1.4f, 1_000).isEmpty())
        assertEquals(setOf(RecipeIds.BRIGHTEN_SCREEN, RecipeIds.INCREASE_TEXT_SIZE),
            monitor.onSnapshot(DisplaySnapshot(70, false, 1.1f), true, 160, 1.4f, 2_000))
    }

    @Test
    fun `느리게 낮아진 값은 자동 복구로 오인하지 않는다`() {
        val monitor = DisplayRescueMonitor()
        monitor.onSnapshot(DisplaySnapshot(160, false, 1.4f), true, 160, 1.4f, 0)
        monitor.onSnapshot(DisplaySnapshot(110, false, 1.2f), true, 160, 1.4f, 1_000)
        assertTrue(monitor.onSnapshot(DisplaySnapshot(70, false, 1.0f), true, 160, 1.4f, 7_000).isEmpty())
    }
}
