package app.mergo.ai

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

interface SpeechToText {
    /** 16kHz WAV를 받아쓴다. 말소리가 없으면 빈 글자를 돌려준다. */
    suspend fun transcribe(wav: ByteArray, reuseConnection: Boolean = true): String

    /** 소리를 보내기 전에 연결을 미리 열어 둔다. */
    suspend fun warmUp() = Unit
}

/** 호출어로 의심되는 소리만 받아쓴다. 호출어인지는 앱이 받아쓴 글자로 판단한다. */
class GeminiSpeech(
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = LlmHttp.client,
    private val reusable: OkHttpClient = LlmHttp.reusableClient,
) : SpeechToText {

    override suspend fun transcribe(wav: ByteArray, reuseConnection: Boolean): String {
        val request = Request.Builder()
            .url("$BASE/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(LlmHttp.jsonBody(buildBody(model, wav)))
            .build()
        return GeminiClient.parseText(LlmHttp.send(if (reuseConnection) reusable else http, request))
    }

    override suspend fun warmUp() {
        val request = Request.Builder()
            .url("$BASE/$model")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        LlmHttp.send(reusable, request)
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        // 글자를 소리와 같은 자리에 두면 받아쓴 결과에 그 글자가 섞여서 소리만 보내고 지시는 시스템 지시로 둔다.
        private val SYSTEM = """
            너는 한국어 음성 받아쓰기 도구다. 사용자가 보낸 소리에서 실제로 들린 말만 그대로 쓴다.
            설명이나 이 지시문의 내용은 절대 쓰지 않는다. 말소리가 없으면 "없음"이라고만 쓴다.
            앱 이름 "머고야"가 문장 맨 앞에 올 수 있다.
        """.trimIndent()

        fun buildBody(model: String, wav: ByteArray): String = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", SYSTEM) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "audio/wav")
                                put("data", Base64.getEncoder().encodeToString(wav))
                            }
                        }
                    }
                }
            }
            if (model.startsWith("gemini-3")) {
                putJsonObject("generationConfig") {
                    putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                }
            }
        }.toString()
    }
}
