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
import org.junit.Assert.assertFalse
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

class FakePorts : MemoryPort, NotePort, DevicePort, HttpPort, SearchPort, ActionPort {
    var memory = ""
    override suspend fun read(): String = memory
    override suspend fun write(op: String, text: String): String {
        memory = if (op == "replace") text else listOf(memory, text).filter { it.isNotBlank() }.joinToString("\n")
        return "ok"
    }
    override suspend fun save(title: String, body: String): String = "saved $title"
    override suspend fun status(): String = """{"battery":80}"""
    override suspend fun fetch(url: String): String = "fetched $url"
    override suspend fun search(query: String): String = "search:$query"
    override suspend fun openUrl(url: String): String = "open:$url"
    override suspend fun shareText(text: String): String = "share:$text"
    override suspend fun openApp(name: String): String = "app:$name"
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
        val runtime = AgentRuntime(llm, ports, ports, ports, ports, ports, ports)
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
        val runtime = AgentRuntime(llm, ports, ports, ports, ports, ports, ports)
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
    fun parsesDuckDuckGoResults() {
        val html = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeepseek.com">DeepSeek</a>
            <a class="result__snippet" href="x">Official V4 Pro is out</a>
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Farxiv.org%2Fabs%2F1">Paper</a>
            <a class="result__snippet" href="x">Million-token context</a>
        """.trimIndent()
        val hits = parseDuckDuckGoHtml(html, 5)
        assertEquals(2, hits.size)
        assertEquals("https://deepseek.com", hits[0].url)
        assertEquals("DeepSeek", hits[0].title)
        assertTrue(hits[0].snippet.contains("V4"))
    }

    @Test
    fun searchToolIsRegistered() {
        val names = museToolDefinitions().map { it.function.name }
        assertTrue(names.contains("web_search"))
        assertTrue(names.contains("open_app"))
    }

    @Test
    fun blocksSearchResultPages() {
        assertTrue(isSearchResultsPage("https://www.google.com/search?q=muse"))
        assertTrue(isSearchResultsPage("https://www.baidu.com/s?wd=muse"))
        assertFalse(isSearchResultsPage("https://deepseek.com/blog"))
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
