package com.mergo.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

class ClaudeClient(
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = LlmHttp.client,
) : LlmClient {

    override suspend fun complete(system: String, user: String): String {
        val request = Request.Builder()
            .url(URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .post(LlmHttp.jsonBody(buildBody(model, system, user)))
            .build()
        return parseText(LlmHttp.send(http, request))
    }

    companion object {
        private const val URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2026-10-21"
        private const val MAX_TOKENS = 1024

        fun buildBody(model: String, system: String, user: String): String = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            // 8초 안에 답해야 하는 분류 작업이라 effort를 낮춘다.
            putJsonObject("output_config") { put("effort", "low") }
        }.toString()

        fun parseText(body: String): String {
            val root = Json.parseToJsonElement(body) as? JsonObject
            val stop = root?.get("stop_reason")?.jsonPrimitive?.contentOrNull
            if (stop == "refusal") throw LlmException("Claude가 요청을 거절함")
            val content = root?.get("content") as? JsonArray
            val text = content.orEmpty()
                .filterIsInstance<JsonObject>()
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            if (text.isBlank()) throw LlmException("Claude 응답에 글자가 없음")
            return text
        }
    }
}
