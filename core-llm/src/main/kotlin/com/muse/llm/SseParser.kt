package com.muse.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class SseParse {
    data object Done : SseParse()
    data object Ignore : SseParse()
    data class Chunk(val chunk: StreamChunk) : SseParse()
    data class Error(val message: String) : SseParse()
}

@Serializable
data class StreamChunk(
    val reasoning: String? = null,
    val content: String? = null,
    val role: String? = null,
    val finishReason: String? = null,
    val toolCallDeltas: List<ToolCallDelta> = emptyList(),
)

@Serializable
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

object SseParser {
    fun parseLine(line: String): SseParse {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return SseParse.Ignore
        if (!trimmed.startsWith("data:")) return SseParse.Ignore
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return SseParse.Ignore
        if (payload == "[DONE]") return SseParse.Done
        return parsePayload(payload)
    }

    fun parsePayload(payload: String): SseParse {
        val element = runCatching { MuseJson.parseToJsonElement(payload) }.getOrElse {
            return SseParse.Error("无法解析模型 Stream：$payload")
        }
        if (element !is JsonObject) return SseParse.Ignore
        element["error"]?.let { err ->
            val message = err.jsonObjectOrNull()
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: err.toString()
            return SseParse.Error(message)
        }
        val choice = element["choices"]
            ?.jsonArrayOrNull()
            ?.firstOrNull()
            ?.jsonObjectOrNull()
            ?: return SseParse.Ignore
        val delta = choice["delta"]?.jsonObjectOrNull()
        val message = choice["message"]?.jsonObjectOrNull()
        val source = delta ?: message
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        if (source == null) {
            return if (finish != null) {
                SseParse.Chunk(StreamChunk(finishReason = finish))
            } else {
                SseParse.Ignore
            }
        }
        val toolDeltas = source["tool_calls"]?.jsonArrayOrNull()?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            val fn = obj["function"]?.jsonObjectOrNull()
            ToolCallDelta(
                index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0,
                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                name = fn?.get("name")?.jsonPrimitive?.contentOrNull,
                arguments = fn?.get("arguments")?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        return SseParse.Chunk(
            StreamChunk(
                reasoning = source["reasoning_content"]?.jsonPrimitive?.contentOrNull,
                content = source["content"]?.jsonPrimitive?.contentOrNull,
                role = source["role"]?.jsonPrimitive?.contentOrNull,
                finishReason = finish,
                toolCallDeltas = toolDeltas,
            ),
        )
    }
}

class StreamAccumulator {
    private val reasoning = StringBuilder()
    private val content = StringBuilder()
    private val tools = linkedMapOf<Int, MutableToolCall>()
    var finishReason: String? = null
        private set

    fun apply(chunk: StreamChunk) {
        if (!chunk.reasoning.isNullOrEmpty()) reasoning.append(chunk.reasoning)
        if (!chunk.content.isNullOrEmpty()) content.append(chunk.content)
        if (chunk.finishReason != null) finishReason = chunk.finishReason
        for (delta in chunk.toolCallDeltas) {
            val slot = tools.getOrPut(delta.index) { MutableToolCall() }
            if (!delta.id.isNullOrEmpty()) slot.id = delta.id
            if (!delta.name.isNullOrEmpty()) slot.name = delta.name
            if (!delta.arguments.isNullOrEmpty()) slot.arguments.append(delta.arguments)
        }
    }

    fun reasoningText(): String = reasoning.toString()
    fun contentText(): String = content.toString()
    fun hasToolCalls(): Boolean = tools.isNotEmpty()

    fun toAssistantMessage(): ChatMessage {
        val calls = tools.entries.sortedBy { it.key }.map { (_, slot) ->
            ToolCall(
                id = slot.id.ifEmpty { "call_${slot.name.ifEmpty { "unknown" }}" },
                function = ToolFunctionCall(
                    name = slot.name,
                    arguments = slot.arguments.toString().ifEmpty { "{}" },
                ),
            )
        }
        return ChatMessage(
            role = "assistant",
            content = content.toString(),
            reasoningContent = reasoning.toString().ifEmpty { null },
            toolCalls = calls.takeIf { it.isNotEmpty() },
        )
    }
}

private class MutableToolCall {
    var id: String = ""
    var name: String = ""
    val arguments = StringBuilder()
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.jsonArrayOrNull() = runCatching { jsonArray }.getOrNull()
