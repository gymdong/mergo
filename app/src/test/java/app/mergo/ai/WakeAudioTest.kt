package app.mergo.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class WakeAudioTest {
    @Test
    fun `버퍼가 차기 전에는 들어온 만큼만 돌려준다`() {
        val ring = AudioRing(4)
        ring.add(shortArrayOf(1, 2, 9), 2)
        assertArrayEquals(shortArrayOf(1, 2), ring.snapshot())
    }

    @Test
    fun `버퍼가 넘치면 최근 소리만 순서대로 남긴다`() {
        val ring = AudioRing(4)
        ring.add(shortArrayOf(1, 2, 3), 3)
        ring.add(shortArrayOf(4, 5, 6), 3)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), ring.snapshot())
    }

    @Test
    fun `최근 소리만 잘라서 꺼낸다`() {
        val ring = AudioRing(4)
        ring.add(shortArrayOf(1, 2, 3, 4, 5), 5)
        assertArrayEquals(shortArrayOf(4, 5), ring.latest(2))
        assertArrayEquals(shortArrayOf(2, 3, 4, 5), ring.latest(99))
    }

    @Test
    fun `목소리가 잦아들면 말이 끝난 것으로 본다`() {
        val detector = SpeechEndDetector(chunkMs = 100)
        repeat(30) { detector.idle(300.0) }
        detector.begin()
        repeat(12) { assertFalse(detector.update(2_000.0)) }
        repeat(4) { assertFalse(detector.update(320.0)) }
        assertTrue(detector.update(320.0))
    }

    @Test
    fun `말이 너무 짧게 끊겨도 최소 시간은 기다린다`() {
        val detector = SpeechEndDetector(chunkMs = 100)
        repeat(30) { detector.idle(300.0) }
        detector.begin()
        repeat(9) { assertFalse(detector.update(310.0)) }
        assertTrue(detector.update(310.0))
    }

    @Test
    fun `소음이 커도 그보다 큰 목소리가 이어지면 끝나지 않는다`() {
        val detector = SpeechEndDetector(chunkMs = 100)
        repeat(30) { detector.idle(1_000.0) }
        detector.begin()
        repeat(30) { assertFalse(detector.update(2_000.0)) }
    }

    @Test
    fun `목소리에 끌려 소음 기준이 올라가지 않는다`() {
        val detector = SpeechEndDetector(chunkMs = 100)
        repeat(30) { detector.idle(300.0) }
        repeat(5) { detector.idle(3_000.0) }
        detector.begin()
        repeat(9) { detector.update(2_000.0) }
        assertFalse(detector.update(2_000.0))
        repeat(4) { assertFalse(detector.update(420.0)) }
        assertTrue(detector.update(420.0))
    }

    @Test
    fun `두 번째 기회는 정해진 시간 동안만 열린다`() {
        val chance = SecondChance(windowMs = 5_000)
        assertFalse(chance.isOpen(1_000))
        chance.open(1_000)
        assertTrue(chance.isOpen(5_999))
        assertFalse(chance.isOpen(6_000))
    }

    @Test
    fun `다시 부르면 두 번째 기회를 닫는다`() {
        val chance = SecondChance(windowMs = 5_000)
        chance.open(0)
        chance.spend()
        assertFalse(chance.isOpen(1))
    }

    @Test
    fun `다시 불러도 실패하면 한 번은 열지 않아 전송이 이어지지 않는다`() {
        val chance = SecondChance(windowMs = 5_000)
        chance.open(0)
        chance.spend()
        chance.open(100)
        assertFalse(chance.isOpen(101))
        chance.open(10_000)
        assertTrue(chance.isOpen(10_001))
    }

    @Test
    fun `다시 불러서 호출어를 확인하면 다음 실패에 또 열린다`() {
        val chance = SecondChance(windowMs = 5_000)
        chance.open(0)
        chance.spend()
        chance.reset()
        chance.open(100)
        assertTrue(chance.isOpen(101))
    }

    @Test
    fun `호출어의 첫 소리만 들리면 못 미친 것으로 본다`() {
        assertTrue(WakeGate.nearMiss("뭐 소리 거 안 요"))
        assertTrue(WakeGate.nearMiss("머"))
        assertFalse(WakeGate.nearMiss("오늘 날씨 좋 다"))
        assertFalse(WakeGate.nearMiss(""))
    }

    @Test
    fun `음량을 계산한다`() {
        assertEquals(0.0, AudioLevel.rms(shortArrayOf(), 0), 0.0)
        assertEquals(100.0, AudioLevel.rms(shortArrayOf(100, -100, 100, -100), 4), 0.001)
    }

    @Test
    fun `이름으로 방법을 끈다`() {
        assertEquals(WakeOptions(), WakeOptions.without(null))
        assertEquals(WakeOptions(trim = false, endDetect = false), WakeOptions.without("trim, end"))
        assertEquals(
            WakeOptions(secondChance = false, trim = false, endDetect = false, warmUp = false),
            WakeOptions.without("second,trim,end,warm"),
        )
    }

    @Test
    fun `WAV 머리글에 형식과 길이를 적는다`() {
        val wav = WavEncoder.encode(shortArrayOf(1, -1), 16_000)
        val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(40, data.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1.toShort(), data.getShort(22))
        assertEquals(16_000, data.getInt(24))
        assertEquals(32_000, data.getInt(28))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(4, data.getInt(40))
        assertEquals((-1).toShort(), data.getShort(46))
    }

    @Test
    fun `호출어 비슷한 소리만 의심한다`() {
        assertTrue(WakeGate.suspects("머 고 야 잘못"))
        assertTrue(WakeGate.suspects("고 야 잘못 눌렀"))
        assertTrue(WakeGate.suspects("뭐 고 야"))
        assertFalse(WakeGate.suspects("오늘 날씨 좋 다"))
        assertFalse(WakeGate.suspects(""))
    }

    @Test
    fun `문법은 JSON 배열이고 unk를 담는다`() {
        val grammar = WakeGate.grammar()
        assertTrue(grammar.startsWith("[\"머\""))
        assertTrue("\"[unk]\"]" in grammar)
    }

    @Test
    fun `Gemini 요청에 소리를 인코딩해 담는다`() {
        val wav = byteArrayOf(1, 2, 3)
        val body = GeminiSpeech.buildBody("gemini-3.5-flash-lite", wav)
        assertTrue("audio/wav" in body)
        assertTrue(Base64.getEncoder().encodeToString(wav) in body)
        assertTrue("\"thinkingLevel\":\"low\"" in body)
    }

    @Test
    fun `지시문은 소리와 분리해서 보낸다`() {
        val body = GeminiSpeech.buildBody("gemini-3.5-flash-lite", byteArrayOf(1))
        assertTrue(body.startsWith("{\"systemInstruction\""))
        assertEquals(1, Regex("\"parts\":\\[\\{\"inlineData\"").findAll(body).count())
        assertTrue("받아써 줘" !in body)
    }

    @Test
    fun `받아쓴 글자로 호출어를 판단한다`() {
        assertEquals(WakeHit("소리가안나"), WakePhrase.find("머고야, 소리가 안 나.", "머고야"))
        assertEquals(null, WakePhrase.find("주민등록증을 보여주시겠어요?", "머고야"))
    }
}
