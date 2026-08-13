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
    override suspend fun shizukuStatus(): String = "shizuku=fake"
    override suspend fun shell(command: String): String = "shell:$command"
    override suspend fun uiDump(): String = "* 设置  [0,0][100,100]"
    override suspend fun tap(x: Int, y: Int): String = "tap $x $y"
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String = "swipe"
    override suspend fun type(text: String): String = "type $text"
    override suspend fun key(name: String): String = "key $name"
    override suspend fun uiStatus(): String = "a11y=fake"
    override suspend fun uiSnapshot(): String = "* n1 OK [button] (100,200)"
    override suspend fun findNodes(query: String): String = "n1 $query"
    override suspend fun clickNode(id: String): String = "click $id"
    override suspend fun clickText(text: String): String = "clickText $text"
    override suspend fun scroll(direction: String): String = "scroll $direction"
    override suspend fun waitMs(ms: Int): String = "wait $ms"
    override suspend fun ocrScreen(): String = "ocr:ok"
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
    fun toolCapPersistsAssistantAndToolTurns() = runTest {
        val ports = FakePorts()
        val llm = ScriptedLlm(
            mutableListOf(
                {
                    ChatMessage(
                        role = "assistant",
                        reasoningContent = "先打开应用",
                        toolCalls = listOf(
                            ToolCall("c1", function = ToolFunctionCall("open_app", """{"name":"x"}""")),
                        ),
                    )
                },
            ),
        )
        val runtime = AgentRuntime(llm, ports, ports, ports, ports, ports, ports, maxRounds = 1)
        val events = runtime.run(emptyList(), "打开应用", config).toList()
        val turns = events.filterIsInstance<AgentEvent.TurnMessage>().map { it.message }
        assertTrue(turns.any { it.role == "assistant" && it.toolCalls != null })
        assertTrue(turns.any { it.role == "tool" && it.name == "open_app" })
        assertTrue(turns.any { it.role == "assistant" && it.content.orEmpty().contains("上限") })
        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test
    fun followUpSeesPriorToolTrace() = runTest {
        val ports = FakePorts()
        val prior = listOf(
            ChatMessage(role = "user", content = "打开应用"),
            ChatMessage(
                role = "assistant",
                reasoningContent = "先开应用",
                toolCalls = listOf(
                    ToolCall("c1", function = ToolFunctionCall("open_app", """{"name":"x"}""")),
                ),
            ),
            ChatMessage(role = "tool", content = "已打开 app.example", toolCallId = "c1", name = "open_app"),
            ChatMessage(role = "assistant", content = "本轮 Tool 次数已达上限（16）。"),
        )
        val llm = ScriptedLlm(
            mutableListOf({ incoming ->
                assertTrue(incoming.any { it.role == "tool" && it.content?.contains("app.example") == true })
                assertTrue(incoming.any { it.reasoningContent == "先开应用" })
                ChatMessage(role = "assistant", content = "上次已经打开了。")
            }),
        )
        val runtime = AgentRuntime(llm, ports, ports, ports, ports, ports, ports)
        val events = runtime.run(prior, "刚才卡在哪", config).toList()
        val done = events.last() as AgentEvent.Completed
        assertEquals("上次已经打开了。", done.assistant.content)
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
    fun observeToolsAreNotRepeatBlocked() = runTest {
        val ports = FakePorts()
        val llm = ScriptedLlm(
            mutableListOf(
                {
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall("s0", function = ToolFunctionCall("ui_snapshot", "{}"))),
                    )
                },
                {
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall("s1", function = ToolFunctionCall("ui_snapshot", "{}"))),
                    )
                },
                {
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall("s2", function = ToolFunctionCall("ui_snapshot", "{}"))),
                    )
                },
                {
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall("s3", function = ToolFunctionCall("ui_snapshot", "{}"))),
                    )
                },
                { ChatMessage(role = "assistant", content = "看到了") },
            ),
        )
        val runtime = AgentRuntime(llm, ports, ports, ports, ports, ports, ports, maxRounds = 8)
        val events = runtime.run(emptyList(), "看屏幕", config).toList()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertTrue(finished.none { it.result.contains("3 次") })
        assertEquals(4, finished.count { it.name == "ui_snapshot" })
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
    fun chatModeSendsNoTools() {
        assertTrue(toolsForRun(emptyList()).isEmpty())
        assertEquals(museToolDefinitions().size, toolsForRun(null).size)
        assertEquals(listOf("web_search"), toolsForRun(listOf("web_search")).map { it.function.name })
    }

    @Test
    fun searchToolIsRegistered() {
        val names = museToolDefinitions().map { it.function.name }
        assertTrue(names.contains("web_search"))
        assertTrue(names.contains("open_app"))
        assertTrue(names.contains("ui_dump"))
        assertTrue(names.contains("tap"))
        assertTrue(names.contains("shell"))
        assertTrue(names.contains("ui_snapshot"))
        assertTrue(names.contains("ocr_screen"))
        assertTrue(names.contains("click_node"))
        assertTrue(names.contains("click_text"))
    }

    @Test
    fun uiSafetyUsesCallerPatternsOnly() {
        val snap = UiSnapshot(
            pkg = "app.example",
            title = "Confirm",
            source = "a11y",
            nodes = listOf(
                UiNode("n1", "Pay now", "", "", "Button", true, false, false, false, true, 1, 1, 0, 0, 2, 2),
            ),
        )
        assertEquals(null, UiSafety.blockReason(snap, emptyList()))
        assertTrue(UiSafety.blockReason(snap, listOf("Pay now"))!!.contains("Pay now"))
    }

    @Test
    fun formatSnapshotRanksClickable() {
        val snap = UiSnapshot(
            "pkg", "title", "a11y",
            listOf(
                UiNode("n1", "说明文字", "", "", "TextView", false, false, false, false, true, 1, 1, 0, 0, 2, 2),
                UiNode("n2", "Continue", "", "", "Button", true, false, false, false, true, 10, 10, 0, 0, 20, 20),
            ),
        )
        val text = formatSnapshot(snap)
        assertTrue(text.indexOf("n2") < text.indexOf("n1"))
        assertTrue(findInSnapshot(snap, "Continue").single().id == "n2")
    }

    @Test
    fun shellPolicyBlocksDanger() {
        assertTrue(ShellPolicy.denyReason("rm -rf /") != null)
        assertTrue(ShellPolicy.denyReason("pm uninstall com.android.settings") != null)
        assertTrue(ShellPolicy.denyReason("curl http://x") != null)
        assertEquals(null, ShellPolicy.denyReason("dumpsys activity top"))
        assertEquals(null, ShellPolicy.denyReason("input tap 100 200"))
        assertEquals(null, ShellPolicy.denyReason("uiautomator dump /data/local/tmp/x.xml"))
        assertEquals(
            null,
            ShellPolicy.denyReason("uiautomator dump /data/local/tmp/muse_ui.xml; cat /data/local/tmp/muse_ui.xml"),
        )
        assertTrue(ShellPolicy.denyReason("uiautomator dump /x >/dev/null 2>&1") != null)
    }

    @Test
    fun formatOcrKeepsCenters() {
        val text = formatOcrHits(
            listOf(OcrHit("热搜", 120, 340), OcrHit("登录", 540, 80)),
            "a11y",
        )
        assertTrue(text.contains("热搜 (120,340)"))
        assertTrue(text.contains("source=a11y"))
    }

    @Test
    fun sameScreenComparesPkgTitleAndLabels() {
        val node = UiNode("n1", "热搜", "", "", "TextView", true, false, false, false, true, 1, 1, 0, 0, 2, 2)
        val a = UiSnapshot("app.example", "Home", "a11y", listOf(node))
        val b = a.copy()
        val c = a.copy(title = "Search")
        assertTrue(sameScreen(a, b))
        assertTrue(!sameScreen(a, c))
    }

    @Test
    fun compactDumpKeepsClickable() {
        val xml = """
            <hierarchy>
              <node text="设置" clickable="true" bounds="[10,20][80,60]" class="android.widget.TextView"/>
              <node text="" clickable="false" bounds="[0,0][1,1]" class="android.view.View"/>
            </hierarchy>
        """.trimIndent()
        val compact = compactUiDump(xml)
        assertTrue(compact.contains("设置"))
        assertTrue(compact.contains("*"))
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
        try {
            UrlGuard.validate("https://192.168.1.1/admin")
            throw AssertionError("expected block")
        } catch (e: UrlBlocked) {
            assertTrue(e.message!!.contains("私网"))
        }
        UrlGuard.validate("https://www.baidu.com")
        UrlGuard.validate("https://www.jiqizhixin.com/articles/1")
    }

    @Test
    fun parsesBingAndBaidu() {
        val bing = parseBingHtml(
            """<h2><a href="https://deepseek.com/blog">DeepSeek V4</a></h2>
               <h2><a href="https://www.bing.com/ck/a">junk</a></h2>""",
            5,
        )
        assertEquals(1, bing.size)
        assertEquals("https://deepseek.com/blog", bing[0].url)
        val baidu = parseBaiduHtml(
            """<div class="result" mu="https://www.jiqizhixin.com/articles/v4"><h3><a>机器之心</a></h3></div>""",
            5,
        )
        assertEquals("https://www.jiqizhixin.com/articles/v4", baidu[0].url)
        assertEquals("机器之心", baidu[0].title)
    }
}
