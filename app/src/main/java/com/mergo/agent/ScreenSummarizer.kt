package com.mergo.agent

import com.mergo.model.NodeSnapshot

object ScreenSummarizer {
    const val MAX_NODES = 60
    private const val MAX_TEXT = 80

    /** 보이는 노드만 남긴다. 60개를 넘으면 누를 수 있는 노드를 먼저 지키고 화면 순서는 유지한다. */
    fun select(nodes: List<NodeSnapshot>): List<NodeSnapshot> {
        val useful = nodes.filter {
            it.visible && (it.clickable || !it.text.isNullOrBlank() || !it.desc.isNullOrBlank())
        }
        if (useful.size <= MAX_NODES) return useful
        val kept = useful.sortedBy { if (it.clickable) 0 else 1 }.take(MAX_NODES).toSet()
        return useful.filter { it in kept }
    }

    fun summarize(nodes: List<NodeSnapshot>): String = select(nodes).joinToString("\n") {
        val b = it.bounds
        "${it.id} | 글자: ${short(it.text)} | 설명: ${short(it.desc)} | " +
            "클릭: ${if (it.clickable) "가능" else "불가"} | 위치: ${b.l},${b.t},${b.r},${b.b}"
    }

    private fun short(text: String?): String =
        text.orEmpty().replace('\n', ' ').take(MAX_TEXT)
}
