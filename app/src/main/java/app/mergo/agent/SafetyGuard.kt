package app.mergo.agent

import app.mergo.model.NodeSnapshot

enum class Verdict { ALLOW, RETRY, STOP }

object SafetyGuard {
    private val DANGER = listOf("설치", "결제", "구매", "보내기", "동의", "삭제", "로그인", "인증")

    // 에뮬레이터 YouTube처럼 영어 화면도 있다. 'replay'의 pay 같은 오탐을 막으려고 단어 단위로 찾는다.
    private val DANGER_EN = Regex(
        "\\b(install|buy|purchase|pay|payment|send|agree|accept|delete|sign in|sign up|log in|login|verify)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isDangerous(node: NodeSnapshot): Boolean =
        listOfNotNull(node.text, node.desc).any { text ->
            DANGER.any { it in text } || DANGER_EN.containsMatchIn(text)
        }

    /**
     * 위험 노드를 고르면 STOP이다. 글자 없는 큰 덮개를 누르면 그 중심의 위험 버튼이 대신 눌릴 수 있어서,
     * 고른 노드 영역 안에 위험 노드의 중심이 있어도 STOP으로 본다.
     */
    fun check(action: AgentAction, nodes: List<NodeSnapshot>): Verdict {
        if (action.action != AgentAction.CLICK) return Verdict.ALLOW
        val target = nodes.firstOrNull { it.id == action.node && it.visible } ?: return Verdict.RETRY
        val danger = isDangerous(target) || nodes.any { it.visible && isDangerous(it) && contains(target, it) }
        return if (danger) Verdict.STOP else Verdict.ALLOW
    }

    private fun contains(outer: NodeSnapshot, inner: NodeSnapshot): Boolean {
        val x = (inner.bounds.l + inner.bounds.r) / 2
        val y = (inner.bounds.t + inner.bounds.b) / 2
        val box = outer.bounds
        return x in box.l..box.r && y in box.t..box.b
    }
}
