package com.mergo.agent

import com.mergo.ai.LlmClient
import com.mergo.ai.LlmException
import com.mergo.ai.RecordingVoice
import com.mergo.ai.ScriptedLlm
import com.mergo.model.Clock
import com.mergo.model.NodeSnapshot
import com.mergo.model.Rect4
import com.mergo.model.ScreenReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC-A1, AC-A2 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenAgentTest {
    private val close = NodeSnapshot(1, "×", null, true, Rect4(1040, 10, 1070, 40), true)
    private val install = NodeSnapshot(2, "설치하기", null, true, Rect4(300, 900, 780, 1000), true)
    private val cover = NodeSnapshot(3, null, null, true, Rect4(0, 0, 1080, 1920), true)
    private val article = NodeSnapshot(4, "기사 본문", null, false, Rect4(0, 100, 1080, 800), true)
    private val hidden = NodeSnapshot(5, "숨은 노드", null, true, Rect4(0, 0, 10, 10), false)

    private class FakeReader(private val nodes: List<NodeSnapshot>) : ScreenReader {
        val clicked = mutableListOf<Int>()
        var backs = 0

        override fun nodes() = nodes

        override fun click(nodeId: Int): Boolean {
            clicked += nodeId
            return true
        }

        override fun back(): Boolean {
            backs++
            return true
        }
    }

    private fun click(node: Int) = """{"action": "click", "node": $node, "reason": "이유", "say": "광고를 닫고 있어요"}"""
    private val done = """{"action": "done", "reason": "닫힘", "say": "닫았어요"}"""

    private fun TestScope.agent(reader: ScreenReader, llm: LlmClient, voice: RecordingVoice = RecordingVoice()) =
        LlmScreenAgent(reader, llm, voice, Clock { testScheduler.currentTime })

    @Test
    fun `작은 닫기 버튼을 누르고 완료한다`() = runTest {
        val reader = FakeReader(listOf(close, install, article))
        val voice = RecordingVoice()
        val ok = agent(reader, ScriptedLlm(replies = listOf(click(1), done)), voice).achieve("광고 닫기")
        assertTrue(ok)
        assertEquals(listOf(1), reader.clicked)
        assertEquals("광고를 닫고 있어요", voice.spoken.first())
    }

    @Test
    fun `위험 노드를 고르면 누르지 않고 즉시 멈춘다`() = runTest {
        val reader = FakeReader(listOf(close, install))
        val llm = ScriptedLlm(replies = listOf(click(2)))
        assertFalse(agent(reader, llm).achieve("광고 닫기"))
        assertTrue(reader.clicked.isEmpty())
        assertEquals(1, llm.calls)
    }

    @Test
    fun `설명에 위험 단어가 있어도 막는다`() {
        val node = NodeSnapshot(9, null, "지금 결제", true, Rect4(0, 0, 10, 10), true)
        val action = AgentAction(AgentAction.CLICK, 9, "", "")
        assertEquals(Verdict.STOP, SafetyGuard.check(action, listOf(node)))
    }

    @Test
    fun `글자 없는 덮개를 눌러 위험 버튼이 눌릴 수 있으면 막는다`() {
        val action = AgentAction(AgentAction.CLICK, 3, "", "")
        assertEquals(Verdict.STOP, SafetyGuard.check(action, listOf(cover, install)))
        assertEquals(Verdict.ALLOW, SafetyGuard.check(AgentAction(AgentAction.CLICK, 1, "", ""), listOf(close, install)))
    }

    @Test
    fun `없는 노드를 고르면 누르지 않고 다음 단계로 넘어간다`() = runTest {
        val reader = FakeReader(listOf(close))
        val ok = agent(reader, ScriptedLlm(replies = listOf(click(77), click(1), done))).achieve("광고 닫기")
        assertTrue(ok)
        assertEquals(listOf(1), reader.clicked)
    }

    @Test
    fun `5단계를 넘기면 6번째 판단을 하지 않고 멈춘다`() = runTest {
        val reader = FakeReader(listOf(close))
        val llm = ScriptedLlm(replies = listOf(click(1)))
        assertFalse(agent(reader, llm).achieve("광고 닫기"))
        assertEquals(5, llm.calls)
        assertEquals(5, reader.clicked.size)
    }

    @Test
    fun `30초를 넘기면 멈춘다`() = runTest {
        val reader = FakeReader(listOf(close))
        val llm = ScriptedLlm(delayMs = 7_500, replies = listOf(click(1)))
        assertFalse(agent(reader, llm).achieve("광고 닫기"))
        assertTrue("30초 안에 끝나야 함: ${testScheduler.currentTime}", testScheduler.currentTime <= 30_000)
        assertTrue(llm.calls < 5)
    }

    @Test
    fun `응답이 늦으면 8초에 끊고 다음 단계로 넘어간다`() = runTest {
        var calls = 0
        val llm = object : LlmClient {
            override suspend fun complete(system: String, user: String): String {
                calls++
                if (calls == 1) delay(20_000)
                return if (calls == 2) click(1) else done
            }
        }
        val reader = FakeReader(listOf(close))
        assertTrue(agent(reader, llm).achieve("광고 닫기"))
        assertEquals(listOf(1), reader.clicked)
    }

    @Test
    fun `아무것도 안 하고 완료라고 하면 실패로 본다`() = runTest {
        assertFalse(agent(FakeReader(listOf(close)), ScriptedLlm(replies = listOf(done))).achieve("광고 닫기"))
    }

    @Test
    fun `LLM 오류가 이어져도 5단계 안에서 끝난다`() = runTest {
        val llm = ScriptedLlm(failure = LlmException("연결 실패"), replies = listOf(done))
        assertFalse(agent(FakeReader(listOf(close)), llm).achieve("광고 닫기"))
        assertEquals(5, llm.calls)
    }

    @Test
    fun `글자 입력 같은 허용되지 않은 행동은 받지 않는다`() {
        assertEquals(null, AgentAction.parse("""{"action": "type", "node": 1, "say": "입력"}"""))
        assertEquals(null, AgentAction.parse("""{"action": "click", "say": "노드 없음"}"""))
    }

    @Test
    fun `요약기는 보이지 않는 노드를 빼고 60개까지만 남긴다`() {
        val many = (1..100).map { NodeSnapshot(it, "글자$it", null, false, Rect4(0, it, 10, it + 1), true) }
        val selected = ScreenSummarizer.select(many + close.copy(id = 500) + hidden.copy(id = 501))
        assertEquals(60, selected.size)
        assertTrue("누를 수 있는 × 는 남아야 함", selected.any { it.id == 500 })
        assertFalse(selected.any { it.id == 501 })
    }

    @Test
    fun `요약에는 번호 글자 설명 클릭 위치가 들어간다`() {
        val line = ScreenSummarizer.summarize(listOf(close))
        assertEquals("1 | 글자: × | 설명:  | 클릭: 가능 | 위치: 1040,10,1070,40", line)
    }
}
