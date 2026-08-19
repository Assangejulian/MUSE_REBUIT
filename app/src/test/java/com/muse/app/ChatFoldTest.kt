package com.muse.app

import com.muse.llm.ChatMessage
import com.muse.llm.ToolCall
import com.muse.llm.ToolFunctionCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFoldTest {
    @Test
    fun foldsAssistantToolChainIntoOneBubble() {
        val messages = listOf(
            ChatMessage(role = "user", content = "打开应用"),
            ChatMessage(
                role = "assistant",
                reasoningContent = "先开应用",
                toolCalls = listOf(
                    ToolCall("c1", function = ToolFunctionCall("open_app", """{"name":"x"}""")),
                ),
            ),
            ChatMessage(role = "tool", content = "已打开", toolCallId = "c1", name = "open_app"),
            ChatMessage(role = "assistant", content = "本轮 Tool 次数已达上限（16）。"),
        )
        val ui = foldMessagesForUi(messages)
        assertEquals(2, ui.size)
        assertEquals("user", ui[0].role)
        assertEquals("先开应用", ui[1].thinking)
        assertEquals(1, ui[1].tools.size)
        assertEquals("已打开", ui[1].tools[0].result)
        assertTrue(ui[1].content.contains("上限"))
        assertTrue(ui[1].error)
    }

    @Test
    fun peelsThoughtTagsOutOfAssistantContent() {
        val ui = foldMessagesForUi(
            listOf(
                ChatMessage(role = "user", content = "hi"),
                ChatMessage(
                    role = "assistant",
                    reasoningContent = "true",
                    content = "<thought>Answering the User\nstep</thought>三月截止。",
                ),
            ),
        )
        assertEquals(2, ui.size)
        assertEquals("Answering the User\nstep", ui[1].thinking)
        assertEquals("三月截止。", ui[1].content)
    }
}
