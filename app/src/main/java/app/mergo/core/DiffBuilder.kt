package app.mergo.core

import app.mergo.model.Baseline

/** 정상 상태와 지금 상태의 차이를 사람이 읽는 문장으로 만든다. Brain/LLM의 diff 입력이다. */
object DiffBuilder {
    fun diff(baseline: Baseline?, now: Baseline, lastChangeByUser: Boolean? = null): List<String> {
        if (baseline == null) return emptyList()
        val how = when (lastChangeByUser) {
            true -> " (직접 누름)"
            false -> " (터치 없음)"
            null -> ""
        }
        val out = mutableListOf<String>()
        if (baseline.pkg != now.pkg) out += "앱: ${baseline.pkg} → ${now.pkg}"
        if (baseline.mediaTitle != null && now.mediaTitle != null && baseline.mediaTitle != now.mediaTitle) {
            out += "영상 제목: ${baseline.mediaTitle} → ${now.mediaTitle}$how"
        }
        if (baseline.volume != now.volume) out += "볼륨: ${baseline.volume} → ${now.volume}"
        if (baseline.online != now.online) out += "인터넷: ${onOff(baseline.online)} → ${onOff(now.online)}"
        return out
    }

    private fun onOff(b: Boolean) = if (b) "연결됨" else "끊김"
}
