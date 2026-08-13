package com.muse.app

import com.muse.app.ui.MdBlock
import com.muse.app.ui.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseTest {
    @Test
    fun parsesHeadingsListsAndBold() {
        val blocks = parseMarkdown(
            """
            ## 标题
            对，这是**加粗**和 `code`。
            - 一项
            - 二项
            1. 先做
            2. 再做
            ```
            dump
            ```
            """.trimIndent(),
        )
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals("标题", (blocks[0] as MdBlock.Heading).text)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks.any { it is MdBlock.Bullets && it.items.size == 2 })
        assertTrue(blocks.any { it is MdBlock.Ordered && it.items.size == 2 })
        assertTrue(blocks.any { it is MdBlock.Code && it.text.contains("dump") })
    }
}
