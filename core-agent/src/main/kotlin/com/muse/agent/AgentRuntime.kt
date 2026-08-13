package com.muse.agent

import com.muse.llm.ChatMessage
import com.muse.llm.ChatRequest
import com.muse.llm.LlmClient
import com.muse.llm.LlmEvent
import com.muse.llm.MuseJson
import com.muse.llm.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class AgentConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val reasoningEffort: String,
    val thinkingEnabled: Boolean,
    val maxTokens: Int,
)

sealed class AgentEvent {
    data class ThinkingDelta(val text: String) : AgentEvent()
    data class ContentDelta(val text: String) : AgentEvent()
    data class ToolStarted(val name: String, val args: String) : AgentEvent()
    data class ToolFinished(val name: String, val result: String, val ok: Boolean) : AgentEvent()
    data class Failed(val message: String) : AgentEvent()
    data class Completed(val assistant: ChatMessage) : AgentEvent()
}

class AgentRuntime(
    private val llm: LlmClient,
    private val memory: MemoryPort,
    private val notes: NotePort,
    private val device: DevicePort,
    private val http: HttpPort,
    private val search: SearchPort,
    private val actions: ActionPort,
    private val maxRounds: Int = MAX_TOOL_ROUNDS,
) {
    fun run(
        history: List<ChatMessage>,
        userText: String,
        config: AgentConfig,
    ): Flow<AgentEvent> = flow {
        val memoryText = runCatching { memory.read() }.getOrDefault("")
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(role = "system", content = SYSTEM_PROMPT)
        messages += ChatMessage(
            role = "system",
            content = if (memoryText.isBlank()) {
                "[memory.md]\n(empty)"
            } else {
                "[memory.md]\n$memoryText"
            },
        )
        messages += history.filter { it.role != "system" }
        messages += ChatMessage(role = "user", content = userText)

        val repeats = LinkedHashMap<String, Int>()
        var lastAssistant = ChatMessage(role = "assistant", content = "")
        var finishedByTool = false

        try {
            repeat(maxRounds) { round ->
                val request = ChatRequest(
                    model = config.model,
                    messages = messages,
                    tools = museToolDefinitions(),
                    maxTokens = config.maxTokens,
                    reasoningEffort = config.reasoningEffort,
                    thinkingEnabled = config.thinkingEnabled,
                )
                var failed: LlmEvent.Failed? = null
                var finished: ChatMessage? = null
                llm.stream(request, config.apiKey, config.baseUrl).collect { event ->
                    when (event) {
                        is LlmEvent.ReasoningDelta -> emit(AgentEvent.ThinkingDelta(event.text))
                        is LlmEvent.ContentDelta -> emit(AgentEvent.ContentDelta(event.text))
                        is LlmEvent.Failed -> failed = event
                        is LlmEvent.Finished -> finished = event.message
                    }
                }
                failed?.let {
                    emit(AgentEvent.Failed(it.message))
                    return@flow
                }
                val assistant = finished ?: run {
                    emit(AgentEvent.Failed("模型没有返回完整消息。"))
                    return@flow
                }
                lastAssistant = assistant
                val calls = assistant.toolCalls.orEmpty()
                if (calls.isEmpty()) {
                    emit(AgentEvent.Completed(assistant))
                    return@flow
                }
                messages += assistant
                for (call in calls) {
                    val key = "${call.function.name}|${call.function.arguments}"
                    val count = (repeats[key] ?: 0) + 1
                    repeats[key] = count
                    emit(AgentEvent.ToolStarted(call.function.name, call.function.arguments))
                    val result = if (count >= 3) {
                        "同一个 Tool 用相同参数调用了 3 次。请停止重复，换策略或直接回答用户。"
                    } else {
                        execute(call)
                    }
                    val clipped = clip(result)
                    val ok = !clipped.startsWith("错误：") && count < 3
                    emit(AgentEvent.ToolFinished(call.function.name, clipped, ok))
                    messages += ChatMessage(
                        role = "tool",
                        content = clipped,
                        toolCallId = call.id,
                        name = call.function.name,
                    )
                    if (call.function.name == "finish") {
                        finishedByTool = true
                    }
                }
                if (finishedByTool) {
                    emit(AgentEvent.Completed(assistant))
                    return@flow
                }
                if (round == maxRounds - 1) {
                    emit(AgentEvent.Failed("本轮 Tool 次数已达上限（$maxRounds）。"))
                    return@flow
                }
            }
            emit(AgentEvent.Completed(lastAssistant))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            emit(AgentEvent.Failed(t.message ?: "Agent 循环失败。"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun execute(call: ToolCall): String {
        val args = parseArgs(call.function.arguments)
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(TOOL_TIMEOUT_MS) {
                when (call.function.name) {
                    "device_status" -> device.status()
                    "memory_read" -> {
                        val text = memory.read()
                        if (text.isBlank()) "(memory.md 为空)" else text
                    }
                    "memory_write" -> {
                        val op = args.string("op").ifBlank { "append" }
                        val text = args.string("text")
                        if (text.isBlank()) "错误：text 不能为空。" else memory.write(op, text)
                    }
                    "note_save" -> notes.save(args.string("title"), args.string("body"))
                    "web_search" -> {
                        val query = args.string("query")
                        if (query.isBlank()) "错误：query 不能为空。" else search.search(query)
                    }
                    "http_fetch" -> {
                        val url = args.string("url")
                        if (url.isBlank()) "错误：url 不能为空。" else http.fetch(url)
                    }
                    "open_url" -> {
                        val url = args.string("url")
                        if (url.isBlank()) "错误：url 不能为空。" else actions.openUrl(url)
                    }
                    "share_text" -> {
                        val text = args.string("text")
                        if (text.isBlank()) "错误：text 不能为空。" else actions.shareText(text)
                    }
                    "open_app" -> {
                        val name = args.string("name")
                        if (name.isBlank()) "错误：name 不能为空。" else actions.openApp(name)
                    }
                    "shizuku_status" -> actions.shizukuStatus()
                    "ui_dump" -> actions.uiDump()
                    "tap" -> {
                        val x = args.int("x")
                        val y = args.int("y")
                        if (x == null || y == null) "错误：tap 需要 x 和 y。" else actions.tap(x, y)
                    }
                    "swipe" -> {
                        val x1 = args.int("x1")
                        val y1 = args.int("y1")
                        val x2 = args.int("x2")
                        val y2 = args.int("y2")
                        if (x1 == null || y1 == null || x2 == null || y2 == null) {
                            "错误：swipe 需要 x1 y1 x2 y2。"
                        } else {
                            actions.swipe(x1, y1, x2, y2)
                        }
                    }
                    "type_text" -> {
                        val text = args.string("text")
                        if (text.isBlank()) "错误：text 不能为空。" else actions.type(text)
                    }
                    "keyevent" -> {
                        val name = args.string("name")
                        if (name.isBlank()) "错误：name 不能为空。" else actions.key(name)
                    }
                    "shell" -> {
                        val command = args.string("command")
                        if (command.isBlank()) "错误：command 不能为空。" else actions.shell(command)
                    }
                    "finish" -> "任务已结束：${args.string("summary")}"
                    else -> "错误：未知 Tool ${call.function.name}"
                }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            "错误：${t.message ?: t::class.java.simpleName}"
        }
    }
}

private fun parseArgs(raw: String): JsonObject {
    if (raw.isBlank()) return JsonObject(emptyMap())
    return runCatching {
        MuseJson.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap())
    }.getOrDefault(JsonObject(emptyMap()))
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int? {
    val primitive = this[key]?.jsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}

private fun clip(text: String): String =
    if (text.length <= TOOL_OUTPUT_LIMIT) text else text.take(TOOL_OUTPUT_LIMIT) + "\n…(已截断)"
