package com.muse.llm

import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun parsesReasoningAndContent() {
        val acc = StreamAccumulator()
        val r = SseParser.parseLine(
            """data: {"choices":[{"delta":{"reasoning_content":"先看问题"}}]}""",
        ) as SseParse.Chunk
        val c = SseParser.parseLine(
            """data: {"choices":[{"delta":{"content":"你好"}}]}""",
        ) as SseParse.Chunk
        acc.apply(r.chunk)
        acc.apply(c.chunk)
        assertEquals("先看问题", acc.reasoningText())
        assertEquals("你好", acc.contentText())
    }

    @Test
    fun parsesSplitToolCallArguments() {
        val acc = StreamAccumulator()
        acc.apply(
            (SseParser.parseLine(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"device_status","arguments":""}}]}}]}""",
            ) as SseParse.Chunk).chunk,
        )
        acc.apply(
            (SseParser.parseLine(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{"}}]}}]}""",
            ) as SseParse.Chunk).chunk,
        )
        acc.apply(
            (SseParser.parseLine(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}""",
            ) as SseParse.Chunk).chunk,
        )
        val message = acc.toAssistantMessage()
        assertEquals(1, message.toolCalls?.size)
        assertEquals("device_status", message.toolCalls!![0].function.name)
        assertEquals("{}", message.toolCalls!![0].function.arguments)
        assertTrue(acc.hasToolCalls())
    }

    @Test
    fun doneAndCommentsAreIgnoredOrFinish() {
        assertEquals(SseParse.Done, SseParser.parseLine("data: [DONE]"))
        assertEquals(SseParse.Ignore, SseParser.parseLine(": keep-alive"))
        assertEquals(SseParse.Ignore, SseParser.parseLine(""))
    }

    @Test
    fun errorPayload() {
        val parsed = SseParser.parseLine(
            """data: {"error":{"message":"missing reasoning_content"}}""",
        )
        assertTrue(parsed is SseParse.Error)
        assertEquals("missing reasoning_content", (parsed as SseParse.Error).message)
    }
}

class ChatMessageApiTest {
    @Test
    fun toolCallEchoesReasoningContent() {
        val json = ChatMessage(
            role = "assistant",
            content = "",
            reasoningContent = "需要先看电量",
            toolCalls = listOf(
                ToolCall("call_1", function = ToolFunctionCall("device_status", "{}")),
            ),
        ).toApiJson()
        assertEquals("需要先看电量", json["reasoning_content"]!!.toString().trim('"'))
        assertTrue(json.containsKey("tool_calls"))
    }

    @Test
    fun toolCallAlwaysHasReasoningKey() {
        val json = ChatMessage(
            role = "assistant",
            content = null,
            reasoningContent = null,
            toolCalls = listOf(
                ToolCall("call_1", function = ToolFunctionCall("finish", """{"summary":"ok"}""")),
            ),
        ).toApiJson()
        assertEquals("\"\"", json["reasoning_content"].toString())
    }

    @Test
    fun toolsAlwaysIncludeTypeFunction() {
        val body = ChatRequest(
            model = MODEL_FLASH,
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = listOf(toolSchema("device_status", "Read battery and time")),
        ).toBody()
        val root = MuseJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        val first = root["tools"]!!.let { it as kotlinx.serialization.json.JsonArray }.first()
            as kotlinx.serialization.json.JsonObject
        assertEquals("\"function\"", first["type"].toString())
        val params = first["function"]!!
            .let { it as kotlinx.serialization.json.JsonObject }["parameters"]
            as kotlinx.serialization.json.JsonObject
        assertEquals("\"object\"", params["type"].toString())
    }

    @Test
    fun requestBodyContainsThinkingObject() {
        val body = ChatRequest(
            model = MODEL_FLASH,
            messages = listOf(ChatMessage(role = "user", content = "hi")),
        ).toBody()
        assertTrue(body.contains("\"thinking\""))
        assertTrue(body.contains("\"type\":\"enabled\""))
        assertTrue(body.contains("reasoning_effort"))
        assertFalse(body.contains("temperature"))
    }

    @Test
    fun geminiBodyAsksForThoughtsNotDeepSeekShape() {
        val body = ChatRequest(
            model = MODEL_GEMINI_FLASH,
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            thinkingEnabled = true,
        ).toBody()
        assertTrue(body.contains("include_thoughts"))
        assertTrue(body.contains("thinking_config"))
        assertFalse(body.contains("reasoning_effort"))
        assertFalse(body.contains("\"type\":\"enabled\""))
        assertTrue(body.contains("gemini-2.5-flash"))
    }

    @Test
    fun endpointNormalization() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            normalizeChatEndpoint("https://api.deepseek.com/"),
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            normalizeChatEndpoint("https://api.deepseek.com/chat/completions"),
        )
    }

    @Test
    fun catalogIdsAreUniqueAndCoverVendors() {
        val ids = MODEL_CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val vendors = MODEL_CATALOG.map { it.provider }.toSet()
        assertTrue(vendors.containsAll(ModelProvider.entries.toSet()))
        assertTrue(MODEL_CATALOG.filter { it.thinking }.all { it.provider == ModelProvider.DeepSeek })
    }

    @Test
    fun customIdDoesNotFallBackToFlash() {
        val opt = modelOption("deepseek-vl-test", ModelProvider.DeepSeek)
        assertEquals("deepseek-vl-test", opt.id)
        assertEquals(ModelProvider.DeepSeek, opt.provider)
        assertTrue(opt.thinking)
        val gemini = modelOption("gemini-custom-x", ModelProvider.Gemini)
        assertEquals("gemini-custom-x", gemini.id)
        assertEquals(ModelProvider.Gemini, gemini.provider)
        assertFalse(gemini.thinking)
    }

    @Test
    fun customGeminiBodyUsesHintNotDeepSeekThinking() {
        val body = ChatRequest(
            model = "gemini-foo",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            thinkingEnabled = true,
            provider = ModelProvider.Gemini,
        ).toBody()
        assertTrue(body.contains("\"model\":\"gemini-foo\""))
        assertTrue(body.contains("include_thoughts"))
        assertFalse(body.contains("\"type\":\"enabled\""))
    }

    @Test
    fun geminiToolCallEchoesThoughtSignature() {
        val extra = kotlinx.serialization.json.buildJsonObject {
            put("google", kotlinx.serialization.json.buildJsonObject { put("thought_signature", "SIG") })
        }
        val json = ChatMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(
                ToolCall(
                    "call_1",
                    function = ToolFunctionCall("ui_snapshot", "{}"),
                    extraContent = extra,
                ),
            ),
        ).toApiJson(includeReasoning = false, includeThoughtSignature = true)
        val calls = json["tool_calls"] as kotlinx.serialization.json.JsonArray
        val first = calls.first() as kotlinx.serialization.json.JsonObject
        val google = (first["extra_content"] as kotlinx.serialization.json.JsonObject)["google"]
            as kotlinx.serialization.json.JsonObject
        assertEquals("\"SIG\"", google["thought_signature"].toString())
        assertFalse(json.containsKey("reasoning_content"))
    }

    @Test
    fun deepseekOmitsThoughtSignature() {
        val extra = kotlinx.serialization.json.buildJsonObject {
            put("google", kotlinx.serialization.json.buildJsonObject { put("thought_signature", "SIG") })
        }
        val json = ChatMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(
                ToolCall(
                    "call_1",
                    function = ToolFunctionCall("ui_snapshot", "{}"),
                    extraContent = extra,
                ),
            ),
        ).toApiJson(includeReasoning = true, includeThoughtSignature = false)
        val calls = json["tool_calls"] as kotlinx.serialization.json.JsonArray
        val first = calls.first() as kotlinx.serialization.json.JsonObject
        assertFalse(first.containsKey("extra_content"))
    }

    @Test
    fun parsesGeminiThoughtSignatureOnToolCall() {
        val acc = StreamAccumulator()
        val parsed = SseParser.parseLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"ui_snapshot","arguments":"{}"},"extra_content":{"google":{"thought_signature":"abc"}}}]}}]}""",
        ) as SseParse.Chunk
        acc.apply(parsed.chunk)
        val msg = acc.toAssistantMessage()
        val extra = msg.toolCalls!!.first().extraContent!!
        val google = extra["google"] as kotlinx.serialization.json.JsonObject
        assertEquals("abc", google["thought_signature"]!!.toString().trim('"'))
    }

    @Test
    fun openaiBodyAsksForReasoningEffort() {
        val body = ChatRequest(
            model = MODEL_GPT_TERRA,
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            thinkingEnabled = true,
        ).toBody()
        assertTrue(body.contains("reasoning_effort"))
        assertFalse(body.contains("\"type\":\"enabled\""))
        assertTrue(body.contains("gpt-5.6-terra"))
    }

    @Test
    fun parsesOpenAiReasoningField() {
        val acc = StreamAccumulator()
        val parsed = SseParser.parseLine(
            """data: {"choices":[{"delta":{"reasoning":"先看界面"}}]}""",
        ) as SseParse.Chunk
        acc.apply(parsed.chunk)
        assertEquals("先看界面", acc.reasoningText())
    }

    @Test
    fun parsesGeminiThoughtText() {
        val acc = StreamAccumulator()
        val parsed = SseParser.parseLine(
            """data: {"choices":[{"delta":{"extra_content":{"google":{"thought":"这是桌面"}}}}]}""",
        ) as SseParse.Chunk
        acc.apply(parsed.chunk)
        assertEquals("这是桌面", acc.reasoningText())
    }

    @Test
    fun ignoresBooleanThoughtFlag() {
        val acc = StreamAccumulator()
        val parsed = SseParser.parseLine(
            """data: {"choices":[{"delta":{"extra_content":{"google":{"thought":true}},"content":"<thought>先想</thought>答案"}}]}""",
        ) as SseParse.Chunk
        acc.apply(parsed.chunk)
        assertEquals("先想", acc.reasoningText())
        assertEquals("答案", acc.contentText())
    }

    @Test
    fun splitsThinkTagsIntoReasoning() {
        val acc = StreamAccumulator()
        acc.apply(
            (SseParser.parseLine(
                """data: {"choices":[{"delta":{"content":"<think>先想"}}]}""",
            ) as SseParse.Chunk).chunk,
        )
        acc.apply(
            (SseParser.parseLine(
                """data: {"choices":[{"delta":{"content":"一步</think>答案"}}]}""",
            ) as SseParse.Chunk).chunk,
        )
        assertEquals("先想一步", acc.reasoningText())
        assertEquals("答案", acc.contentText())
    }

    @Test
    fun parsesContentArrayThinkingPart() {
        val acc = StreamAccumulator()
        val parsed = SseParser.parseLine(
            """data: {"choices":[{"delta":{"content":[{"type":"thinking","thinking":"推理中"},{"type":"text","text":"好的"}]}}]}""",
        ) as SseParse.Chunk
        acc.apply(parsed.chunk)
        assertEquals("推理中", acc.reasoningText())
        assertEquals("好的", acc.contentText())
    }
}
