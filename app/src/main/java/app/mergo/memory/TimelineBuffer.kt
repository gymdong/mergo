package app.mergo.memory

import app.mergo.model.Clock
import app.mergo.model.EventKind
import app.mergo.model.ScreenEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 최근 10분 화면 기록을 메모리에만 보관하는 버퍼.
 * 0.5초 안에 같은 내용이 반복되는 화면 변화 이벤트는 하나로 묶는다.
 */
class TimelineBuffer(
    private val clock: Clock,
    private val windowMs: Long = 10 * 60_000L,
    private val maxEvents: Int = 3000,
    private val mergeMs: Long = 500L,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val events = ArrayDeque<ScreenEvent>()

    @Synchronized
    fun add(e: ScreenEvent): Boolean {
        val last = events.lastOrNull()
        if (last != null && isDuplicate(last, e)) return false
        events.addLast(e)
        trim(clock.now())
        return true
    }

    @Synchronized
    fun snapshot(): List<ScreenEvent> {
        trim(clock.now())
        return events.toList()
    }

    @Synchronized
    fun since(ts: Long): List<ScreenEvent> = events.filter { it.ts >= ts }

    /** LLM에 보낼 타임라인 요약. 제목이 바뀐 지점만 남긴다. 예: "14:02 com.google.android.youtube · 제목 (자동)" */
    @Synchronized
    fun summaryLines(limit: Int = 12): List<String> {
        val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val lines = mutableListOf<String>()
        var lastKey: String? = null
        var lastUserTs = Long.MIN_VALUE
        for (e in events) {
            if (e.userAction) lastUserTs = e.ts
            val key = "${e.pkg}|${e.title}"
            if (key == lastKey) continue
            lastKey = key
            val auto = if (e.ts - lastUserTs > 2_000) " (자동)" else ""
            lines += "${fmt.format(Instant.ofEpochMilli(e.ts))} ${e.pkg} · ${e.title ?: "-"}$auto"
        }
        return lines.takeLast(limit)
    }

    @Synchronized
    fun toJson(): String = json.encodeToString(events.toList())

    private fun isDuplicate(a: ScreenEvent, b: ScreenEvent): Boolean =
        a.kind == EventKind.CONTENT_CHANGED && b.kind == EventKind.CONTENT_CHANGED &&
            b.ts - a.ts <= mergeMs &&
            a.pkg == b.pkg && a.title == b.title && a.texts == b.texts && a.markers == b.markers

    private fun trim(now: Long) {
        while (events.isNotEmpty() && (now - events.first().ts > windowMs || events.size > maxEvents)) {
            events.removeFirst()
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): List<ScreenEvent> = json.decodeFromString(text)
    }
}
