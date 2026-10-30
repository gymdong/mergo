package app.mergo.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 해결하지 못한 막힘과 '가족에게 알리기' 기록. MVP는 실제로 보내지 않고 기기 안에만 남긴다. 가족 리포트 화면이 읽는다. */
class FamilyLog(private val file: File?) {
    @Serializable
    data class Entry(
        val ts: Long,
        val situation: String,            // 어르신의 말이나 감지 내용
        val recipe: String,
        val notified: Boolean,            // 가족에게 알리기에 동의했나 (MVP는 실제 전송 없음)
        val resolved: Boolean = false,    // 머고가 해결했나 (리포트의 '자동 해결')
        val summary: String = "",         // 리포트에 보일 한 줄. 예: "소리를 다시 켰어요"
    )

    private val entries = mutableListOf<Entry>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { entries += json.decodeFromString<List<Entry>>(f.readText()) }
        }
    }

    @Synchronized
    fun add(e: Entry) {
        entries += e
        file?.let { runCatching { it.writeText(json.encodeToString(entries.toList())) } }
    }

    @Synchronized
    fun all(): List<Entry> = entries.toList()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
