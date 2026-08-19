package com.muse.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    val extraContent: JsonObject? = null,
)

@Serializable
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val extraContent: JsonObject? = null,
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
                extraContent = thoughtExtra(obj) ?: fn?.let { thoughtExtra(it) },
            )
        }.orEmpty()
        val (content, partReasoning) = extractContent(source)
        val fieldReasoning = extractReasoning(source)
        val reasoning = listOfNotNull(fieldReasoning, partReasoning)
            .filter { it.isNotEmpty() }
            .joinToString("")
            .ifEmpty { null }
        return SseParse.Chunk(
            StreamChunk(
                reasoning = reasoning,
                content = content,
                role = source["role"]?.jsonPrimitive?.contentOrNull,
                finishReason = finish,
                toolCallDeltas = toolDeltas,
                extraContent = thoughtExtra(source),
            ),
        )
    }
}

data class AppliedDelta(
    val reasoning: String? = null,
    val content: String? = null,
)

class StreamAccumulator {
    private val reasoning = StringBuilder()
    private val content = StringBuilder()
    private val tools = linkedMapOf<Int, MutableToolCall>()
    private var messageExtra: JsonObject? = null
    private var inThinkTag = false
    var finishReason: String? = null
        private set

    fun apply(chunk: StreamChunk): AppliedDelta {
        val r0 = reasoning.length
        val c0 = content.length
        if (!chunk.reasoning.isNullOrEmpty() && isDisplayableThought(chunk.reasoning)) {
            reasoning.append(chunk.reasoning)
        }
        if (!chunk.content.isNullOrEmpty()) ingestContent(chunk.content)
        if (chunk.finishReason != null) finishReason = chunk.finishReason
        if (chunk.extraContent != null) messageExtra = chunk.extraContent
        for (delta in chunk.toolCallDeltas) {
            val slot = tools.getOrPut(delta.index) { MutableToolCall() }
            if (!delta.id.isNullOrEmpty()) slot.id = delta.id
            if (!delta.name.isNullOrEmpty()) slot.name = delta.name
            if (!delta.arguments.isNullOrEmpty()) slot.arguments.append(delta.arguments)
            if (delta.extraContent != null) slot.extraContent = delta.extraContent
        }
        return AppliedDelta(
            reasoning = reasoning.substring(r0).ifEmpty { null },
            content = content.substring(c0).ifEmpty { null },
        )
    }

    private fun ingestContent(raw: String) {
        var i = 0
        while (i < raw.length) {
            if (!inThinkTag) {
                val hit = findThinkTag(raw, i, THINK_OPEN)
                if (hit == null) {
                    content.append(raw, i, raw.length)
                    return
                }
                if (hit.first > i) content.append(raw, i, hit.first)
                i = hit.first + hit.second
                inThinkTag = true
            } else {
                val hit = findThinkTag(raw, i, THINK_CLOSE)
                if (hit == null) {
                    reasoning.append(raw, i, raw.length)
                    return
                }
                if (hit.first > i) reasoning.append(raw, i, hit.first)
                i = hit.first + hit.second
                inThinkTag = false
            }
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
                extraContent = slot.extraContent ?: messageExtra,
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
    var extraContent: JsonObject? = null
}

private val THINK_OPEN = listOf("<think>", "<thinking>", "<thought>")
private val THINK_CLOSE = listOf("</think>", "</thinking>", "</thought>")

fun splitThoughtMarkup(text: String): Pair<String, String> {
    if (text.isEmpty()) return "" to ""
    val acc = StreamAccumulator()
    acc.apply(StreamChunk(content = text))
    return acc.contentText() to acc.reasoningText()
}

fun isDisplayableThought(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isNotEmpty() && !trimmed.equals("true", ignoreCase = true) &&
        !trimmed.equals("false", ignoreCase = true)
}

private fun findThinkTag(raw: String, start: Int, tags: List<String>): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    for (tag in tags) {
        val idx = raw.indexOf(tag, start, ignoreCase = true)
        if (idx >= 0 && (best == null || idx < best.first)) {
            best = idx to tag.length
        }
    }
    return best
}

private fun extractReasoning(source: JsonObject): String? {
    source.stringField("reasoning_content", "reasoning", "thinking")
        ?.takeIf { isDisplayableThought(it) }
        ?.let { return it }
    googleThought(source)?.let { return it }
    val details = source["reasoning_details"]?.jsonArrayOrNull() ?: return null
    val texts = details.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.contentOrNull
            is JsonObject -> el.stringField("text", "content")
            else -> null
        }
    }.filter { it.isNotEmpty() }
    return texts.joinToString("").ifEmpty { null }
}

private fun extractContent(source: JsonObject): Pair<String?, String?> {
    val raw = source["content"] ?: return null to null
    return when (raw) {
        is JsonPrimitive -> raw.contentOrNull to null
        is JsonArray -> splitContentParts(raw)
        else -> null to null
    }
}

private fun splitContentParts(arr: JsonArray): Pair<String?, String?> {
    val content = StringBuilder()
    val reason = StringBuilder()
    for (el in arr) {
        val obj = el.jsonObjectOrNull() ?: continue
        val type = obj.stringField("type").orEmpty().lowercase()
        val text = obj.stringField("text", "content", "thinking", "reasoning", "thought")
        if (text.isNullOrEmpty()) continue
        if (type.contains("reason") || type.contains("think")) reason.append(text)
        else content.append(text)
    }
    return content.toString().ifEmpty { null } to reason.toString().ifEmpty { null }
}

private fun googleThought(source: JsonObject): String? {
    val google = source["extra_content"]?.jsonObjectOrNull()?.get("google")?.jsonObjectOrNull()
        ?: source["google"]?.jsonObjectOrNull()
        ?: return null
    google.stringField("thought", "thinking")?.takeIf { isDisplayableThought(it) }?.let { return it }
    val thoughts = google["thoughts"]?.jsonArrayOrNull() ?: return null
    val parts = thoughts.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.contentOrNull
            is JsonObject -> el.stringField("thought", "text", "content")
            else -> null
        }
    }.filter { it.isNotEmpty() }
    return parts.joinToString("").ifEmpty { null }
}

private fun JsonObject.stringField(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        if (!value.isString) continue
        val text = value.content
        if (text.isNotEmpty()) return text
    }
    return null
}

private fun thoughtExtra(obj: JsonObject): JsonObject? {
    obj["extra_content"]?.jsonObjectOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    val sig = obj["thought_signature"]?.jsonPrimitive?.contentOrNull
        ?: obj["google"]?.jsonObjectOrNull()?.get("thought_signature")?.jsonPrimitive?.contentOrNull
    if (sig.isNullOrEmpty()) return null
    return buildJsonObject {
        put("google", buildJsonObject { put("thought_signature", sig) })
    }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.jsonArrayOrNull() = runCatching { jsonArray }.getOrNull()
