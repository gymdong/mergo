package app.mergo.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FamilyLogTest {
    @Test
    fun `결과 분류가 없던 예전 기록도 읽는다`() {
        val file = File.createTempFile("family_log", ".json").apply { deleteOnExit() }
        file.writeText("""[{"ts":1,"situation":"소리가 안 나","recipe":"set_volume","notified":false,"resolved":true,"summary":"소리를 다시 켰어요"}]""")
        val entry = FamilyLog(file).all().single()
        assertEquals("소리를 다시 켰어요", entry.summary)
        assertEquals("", entry.outcome)
    }

    @Test
    fun `결과 분류를 저장하고 다시 읽는다`() {
        val file = File.createTempFile("family_log", ".json").apply { delete(); deleteOnExit() }
        FamilyLog(file).add(FamilyLog.Entry(1, "광고", "skip_ad", notified = false, resolved = false, summary = "안내", outcome = "guided"))
        assertEquals("guided", FamilyLog(file).all().single().outcome)
    }
}
