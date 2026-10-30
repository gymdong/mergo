package com.mergo.ai

import com.mergo.model.YesNo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC-B6 */
class LlmJsonTest {
    private val valid =
        """{"diagnosis": "자동재생", "recipe": "restore_video", "params": {"title": "○○"}, "say": "돌아갈까요?", "confirm": true}"""

    @Test
    fun `정상 JSON을 읽는다`() {
        val decision = LlmJson.parseDecision(valid)!!
        assertEquals(RecipeId.RESTORE_VIDEO, decision.recipe)
        assertEquals("○○", decision.params["title"])
    }

    @Test
    fun `코드펜스로 감싼 JSON을 읽는다`() {
        val fenced = "```json\n$valid\n```"
        assertEquals(RecipeId.RESTORE_VIDEO, LlmJson.parseDecision(fenced)?.recipe)
    }

    @Test
    fun `앞뒤에 설명이 붙어도 읽는다`() {
        assertEquals(RecipeId.RESTORE_VIDEO, LlmJson.parseDecision("네, 결과입니다: $valid 끝.")?.recipe)
    }

    @Test
    fun `깨진 JSON과 목록에 없는 레시피는 null이다`() {
        assertNull(LlmJson.parseDecision("""{"recipe": "restore_video", "say": """))
        assertNull(LlmJson.parseDecision("""{"recipe": , "say": "x"}"""))
        assertNull(LlmJson.parseDecision("""{"recipe": "format_phone", "say": "x"}"""))
        assertNull(LlmJson.parseDecision("""{"recipe": "ask"}"""))
        assertNull(LlmJson.parseDecision("죄송합니다"))
    }

    @Test
    fun `Gemini 요청에는 시스템 지시와 JSON 응답 설정이 들어간다`() {
        val body = GeminiClient.buildBody("gemini-3.6-flash", "시스템", "사용자")
        assertTrue("systemInstruction" in body)
        assertTrue("application/json" in body)
        assertTrue("\"thinkingLevel\":\"low\"" in body)
    }

    @Test
    fun `Gemini 응답의 parts를 이어 붙인다`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"{\"a\""},{"text":":1}"}]}}]}"""
        assertEquals("""{"a":1}""", GeminiClient.parseText(body))
        assertThrows(LlmException::class.java) { GeminiClient.parseText("""{"candidates":[]}""") }
    }

    @Test
    fun `Claude 요청은 temperature 없이 보낸다`() {
        val body = ClaudeClient.buildBody("claude-haiku-5-5", "시스템", "사용자")
        assertTrue("claude-haiku-5-5" in body)
        assertTrue("\"system\":\"시스템\"" in body)
        assertFalse("temperature" in body)
    }

    @Test
    fun `Claude 응답은 text 블록만 읽고 거절은 오류로 본다`() {
        val body = """{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"답"}]}"""
        assertEquals("답", ClaudeClient.parseText(body))
        assertThrows(LlmException::class.java) {
            ClaudeClient.parseText("""{"stop_reason":"refusal","content":[]}""")
        }
    }

    @Test
    fun `키가 없으면 비활성 클라이언트를 만든다`() {
        assertTrue(LlmFactory.create("gemini", "", "m", "", "m") is DisabledLlmClient)
        assertTrue(LlmFactory.create("claude", "키", "m", "", "m") is DisabledLlmClient)
        assertTrue(LlmFactory.create("gemini", "키", "m", "", "m") is GeminiClient)
        assertTrue(LlmFactory.create("claude", "", "m", "키", "m") is ClaudeClient)
    }

    @Test
    fun `예 아니오를 구분한다`() {
        listOf("응", "네", "네!", "예", "그래", "어", "맞아", "그래 좋아").forEach {
            assertEquals(it, YesNo.YES, YesNoParser.parse(it))
        }
        listOf("아니", "아니요", "됐어", "싫어", "아니 됐어").forEach {
            assertEquals(it, YesNo.NO, YesNoParser.parse(it))
        }
        listOf(null, "", "어디 갔어", "뭐라고").forEach {
            assertEquals("$it", YesNo.NONE, YesNoParser.parse(it))
        }
    }
}
