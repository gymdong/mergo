package com.mergo.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface LlmClient {
    suspend fun complete(system: String, user: String): String
}

class LlmException(message: String) : Exception(message)

/** API 키가 없을 때 쓴다. 빌드는 되고 LLM 호출만 꺼진다. */
class DisabledLlmClient : LlmClient {
    override suspend fun complete(system: String, user: String): String =
        throw LlmException("LLM 비활성화 (API 키 없음)")
}

object LlmFactory {
    fun create(
        provider: String,
        geminiKey: String,
        geminiModel: String,
        claudeKey: String,
        claudeModel: String,
    ): LlmClient = when {
        provider == "claude" && claudeKey.isNotBlank() -> ClaudeClient(claudeKey, claudeModel)
        provider != "claude" && geminiKey.isNotBlank() -> GeminiClient(geminiKey, geminiModel)
        else -> DisabledLlmClient()
    }
}

internal object LlmHttp {
    const val TIMEOUT_MS = 8_000L
    private val JSON = "application/json".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    fun jsonBody(json: String): RequestBody = json.toRequestBody(JSON)

    suspend fun send(http: OkHttpClient, request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(LlmException("LLM 연결 실패: ${e.javaClass.simpleName}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                    if (body != null) {
                        cont.resume(body)
                    } else {
                        cont.resumeWithException(LlmException("LLM 응답 오류: HTTP ${response.code}"))
                    }
                }
            })
        }
}
