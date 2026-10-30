package app.mergo.ai

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

class GeminiClient(
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = LlmHttp.client,
) : LlmClient {

    override suspend fun complete(system: String, user: String): String {
        val request = Request.Builder()
            .url("$BASE/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(LlmHttp.jsonBody(buildBody(model, system, user)))
            .build()
        return parseText(LlmHttp.send(http, request))
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"

        fun buildBody(model: String, system: String, user: String): String = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", user) } }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                if (model.startsWith("gemini-3")) {
                    putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                }
            }
        }.toString()

        fun parseText(body: String): String {
            val root = Json.parseToJsonElement(body) as? JsonObject
            val first = (root?.get("candidates") as? JsonArray)?.firstOrNull() as? JsonObject
            val content = first?.get("content") as? JsonObject
            val parts = content?.get("parts") as? JsonArray
            val text = parts.orEmpty().joinToString("") {
                (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            if (text.isBlank()) throw LlmException("Gemini 응답에 글자가 없음")
            return text
        }
    }
}
