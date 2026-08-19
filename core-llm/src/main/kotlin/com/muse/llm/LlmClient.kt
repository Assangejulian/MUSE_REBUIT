package com.muse.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class LlmEvent {
    data class ReasoningDelta(val text: String) : LlmEvent()
    data class ContentDelta(val text: String) : LlmEvent()
    data class Finished(val message: ChatMessage) : LlmEvent()
    data class Failed(val message: String, val httpCode: Int? = null) : LlmEvent()
}

interface LlmClient {
    fun stream(request: ChatRequest, apiKey: String, baseUrl: String): Flow<LlmEvent>
}

class DeepSeekClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val allowLoopback: Boolean = false,
) : LlmClient {
    override fun stream(request: ChatRequest, apiKey: String, baseUrl: String): Flow<LlmEvent> = flow {
        if (apiKey.isBlank()) {
            emit(LlmEvent.Failed("还没有填写 API Key。"))
            return@flow
        }
        if (!isAllowedEndpoint(baseUrl, allowLoopback)) {
            emit(LlmEvent.Failed("只允许 HTTPS 的 Base URL。"))
            return@flow
        }
        val url = normalizeChatEndpoint(baseUrl)
        val body = request.toBody()
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "text/event-stream")
                .header("User-Agent", "Muse/0.1.0")
                .post(body.toRequestBody(JSON))
                .build(),
        )
        val response = try {
            call.execute()
        } catch (io: IOException) {
            emit(LlmEvent.Failed("网络不可用：${io.message ?: "连接失败"}"))
            return@flow
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val raw = resp.body?.string().orEmpty()
                emit(LlmEvent.Failed(humanizeHttpError(resp.code, raw), resp.code))
                return@flow
            }
            val source = resp.body?.source()
            if (source == null) {
                emit(LlmEvent.Failed("模型没有返回内容。", resp.code))
                return@flow
            }
            val acc = StreamAccumulator()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when (val parsed = SseParser.parseLine(line)) {
                    SseParse.Ignore -> Unit
                    SseParse.Done -> {
                        emit(LlmEvent.Finished(acc.toAssistantMessage()))
                        return@flow
                    }
                    is SseParse.Error -> {
                        emit(LlmEvent.Failed(parsed.message, resp.code))
                        return@flow
                    }
                    is SseParse.Chunk -> {
                        val applied = acc.apply(parsed.chunk)
                        if (!applied.reasoning.isNullOrEmpty()) emit(LlmEvent.ReasoningDelta(applied.reasoning))
                        if (!applied.content.isNullOrEmpty()) emit(LlmEvent.ContentDelta(applied.content))
                    }
                }
            }
            emit(LlmEvent.Finished(acc.toAssistantMessage()))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

fun humanizeHttpError(code: Int, raw: String): String {
    val parsed = extractErrorMessage(raw)
    return when (code) {
        400 -> "请求被拒绝（400）${parsed?.let { "：$it" } ?: "。常见原因是 Tool 之后没有回传 reasoning_content / thought_signature。"}"
        401 -> "API Key 无效或已过期。"
        402 -> "账户余额不足。"
        429 -> "请求过于频繁，请稍后再试。"
        in 500..599 -> "DeepSeek 服务暂时不可用（$code）。"
        else -> "模型请求失败（$code）${parsed?.let { "：$it" } ?: ""}"
    }
}

private fun extractErrorMessage(raw: String): String? {
    if (raw.isBlank()) return null
    return runCatching {
        val root = MuseJson.parseToJsonElement(raw)
        val obj = root as? kotlinx.serialization.json.JsonObject ?: return@runCatching raw.take(180)
        val err = obj["error"]
        when (err) {
            is kotlinx.serialization.json.JsonObject ->
                err["message"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            is kotlinx.serialization.json.JsonPrimitive -> err.content
            else -> obj["message"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }
    }.getOrNull()
}

