package com.mergo.agent

import com.mergo.ai.LlmJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class AgentAction(
    val action: String,
    val node: Int?,
    val reason: String,
    val say: String,
) {
    companion object {
        const val CLICK = "click"
        const val BACK = "back"
        const val DONE = "done"
        const val FAIL = "fail"

        private val ALLOWED = setOf(CLICK, BACK, DONE, FAIL)

        fun parse(raw: String): AgentAction? {
            val obj = LlmJson.extractObject(raw) ?: return null
            val action = LlmJson.text(obj, "action")?.takeIf { it in ALLOWED } ?: return null
            val node = (obj["node"] as? JsonPrimitive)?.intOrNull
            if (action == CLICK && node == null) return null
            return AgentAction(
                action = action,
                node = node,
                reason = LlmJson.text(obj, "reason").orEmpty(),
                say = LlmJson.text(obj, "say").orEmpty(),
            )
        }
    }
}
