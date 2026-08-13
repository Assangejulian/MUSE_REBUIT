package com.muse.llm

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
}
