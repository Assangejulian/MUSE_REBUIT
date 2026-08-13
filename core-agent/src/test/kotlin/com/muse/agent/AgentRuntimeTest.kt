package com.muse.agent

import com.muse.llm.ChatMessage
import com.muse.llm.ChatRequest
import com.muse.llm.LlmClient
import com.muse.llm.LlmEvent
import com.muse.llm.ToolCall
import com.muse.llm.ToolFunctionCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptedLlm(
    private val scripts: MutableList<(List<ChatMessage>) -> ChatMessage>,
) : LlmClient {
    val seen = mutableListOf<List<ChatMessage>>()

    override fun stream(request: ChatRequest, apiKey: String, baseUrl: String): Flow<LlmEvent> = flow {
        seen += request.messages
        val next = scripts.removeFirst()
        val message = next(request.messages)
        message.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
            emit(LlmEvent.ReasoningDelta(it))
        }
        message.content?.takeIf { it.isNotEmpty() }?.let {
            emit(LlmEvent.ContentDelta(it))
        }
        emit(LlmEvent.Finished(message))
    }
}

class FakePorts : MemoryPort, NotePort, DevicePort, HttpPort {
    var memory = ""
    override suspend fun read(): String = memory
    override suspend fun write(op: String, text: String): String {
        memory = if (op == "replace") text else listOf(memory, text).filter { it.isNotBlank() }.joinToString("\n")
        return "ok"
    }
    override suspend fun save(title: String, body: String): String = "saved $title"
    override suspend fun status(): String = """{"battery":80}"""
    override suspend fun fetch(url: String): String = "fetched $url"
}

class AgentRuntimeTest {
    private val config = AgentConfig(
        apiKey = "test",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-v4-flash",
        reasoningEffort = "high",
        thinkingEnabled = true,
        maxTokens = 1024,
    )

    @Test
    fun toolThenAnswerEchoesReasoning() = runTest {
        val ports = FakePorts()
        val llm = ScriptedLlm(
            mutableListOf(
                {
                    ChatMessage(
                        role = "assistant",
                        content = "",
                        reasoningContent = "先查电量",
                        toolCalls = listOf(
                            ToolCall("call_1", function = ToolFunctionCall("device_status", "{}")),
                        ),
                    )
                },
                { incoming ->
                    val lastAssistant = incoming.last { it.role == "assistant" }
                    assertEquals("先查电量", lastAssistant.reasoningContent)
                    assertTrue(lastAssistant.toolCalls != null)
                    ChatMessage(role = "assistant", content = "电量大约 80%。")
                },
            ),
        )
        val runtime = AgentRuntime(llm, ports, ports, ports, ports)
        val events = runtime.run(emptyList(), "电量多少", config).toList()
        assertTrue(events.any { it is AgentEvent.ToolStarted && it.name == "device_status" })
        val done = events.last() as AgentEvent.Completed
        assertEquals("电量大约 80%。", done.assistant.content)
    }

    @Test
    fun stopsRepeatingSameTool() = runTest {
        val ports = FakePorts()
        val llm = ScriptedLlm(
            MutableList(4) {
                {
                    ChatMessage(
                        role = "assistant",
                        reasoningContent = "再试一次",
                        toolCalls = listOf(
                            ToolCall("c$it", function = ToolFunctionCall("device_status", "{}")),
                        ),
                    )
                }
            },
        )
        val runtime = AgentRuntime(llm, ports, ports, ports, ports)
        val events = runtime.run(emptyList(), "电量", config).toList()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertTrue(finished.any { it.result.contains("3 次") })
    }

    @Test
    fun htmlToTextStripsTags() {
        val text = htmlToText("<html><head><style>p{}</style></head><body><h1>Hi</h1><p>A&nbsp;B</p></body></html>")
        assertEquals("Hi\n A B", text)
    }

    @Test
    fun urlGuardBlocksPrivate() {
        try {
            UrlGuard.validate("http://example.com")
            throw AssertionError("expected block")
        } catch (e: UrlBlocked) {
            assertTrue(e.message!!.contains("https"))
        }
        try {
            UrlGuard.validate("https://localhost/secret")
            throw AssertionError("expected block")
        } catch (e: UrlBlocked) {
            assertTrue(e.message!!.contains("localhost"))
        }
    }
}
