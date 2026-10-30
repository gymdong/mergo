package com.mergo.report

import com.mergo.model.FamilyLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyLogCodecTest {
    @Test
    fun `저장한 기록을 그대로 읽는다`() {
        val entries = listOf(FamilyLogEntry(1_000, "소리를 다시 켰어요", "자동 해결"))
        assertEquals(entries, FamilyLogCodec.decode(FamilyLogCodec.encode(entries)))
    }

    @Test
    fun `비었거나 깨진 값은 빈 목록이다`() {
        assertTrue(FamilyLogCodec.decode(null).isEmpty())
        assertTrue(FamilyLogCodec.decode("{깨짐").isEmpty())
    }
}
