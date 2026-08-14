package com.muse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import com.muse.design.LocalPalette
import com.muse.design.LocalMuseStyle

internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Bullets(val items: List<String>) : MdBlock()
    data class Ordered(val items: List<String>) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data object Rule : MdBlock()
}

internal fun parseMarkdown(raw: String): List<MdBlock> {
    val lines = raw.replace("\r\n", "\n").split('\n')
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    val bullets = ArrayList<String>()
    val ordered = ArrayList<String>()
    var inCode = false
    val code = StringBuilder()

    fun flushPara() {
        val text = para.toString().trim()
        if (text.isNotEmpty()) out += MdBlock.Paragraph(text)
        para.clear()
    }

    fun flushLists() {
        if (bullets.isNotEmpty()) {
            out += MdBlock.Bullets(bullets.toList())
            bullets.clear()
        }
        if (ordered.isNotEmpty()) {
            out += MdBlock.Ordered(ordered.toList())
            ordered.clear()
        }
    }

    for (line in lines) {
        if (inCode) {
            if (line.trimStart().startsWith("```")) {
                out += MdBlock.Code(code.toString().trimEnd())
                code.clear()
                inCode = false
            } else {
                if (code.isNotEmpty()) code.append('\n')
                code.append(line)
            }
            continue
        }
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                flushLists()
                inCode = true
            }
            trimmed.matches(Regex("^#{1,4}\\s+.+")) -> {
                flushPara()
                flushLists()
                val level = trimmed.takeWhile { it == '#' }.length
                out += MdBlock.Heading(level, trimmed.drop(level).trim())
            }
            trimmed == "---" || trimmed == "***" -> {
                flushPara()
                flushLists()
                out += MdBlock.Rule
            }
            trimmed.startsWith("> ") || trimmed == ">" -> {
                flushPara()
                flushLists()
                out += MdBlock.Quote(trimmed.removePrefix(">").trim())
            }
            trimmed.matches(Regex("^[-*]\\s+.+")) -> {
                flushPara()
                if (ordered.isNotEmpty()) flushLists()
                bullets += trimmed.replace(Regex("^[-*]\\s+"), "")
            }
            trimmed.matches(Regex("^\\d+\\.\\s+.+")) -> {
                flushPara()
                if (bullets.isNotEmpty()) flushLists()
                ordered += trimmed.replace(Regex("^\\d+\\.\\s+"), "")
            }
            trimmed.isEmpty() -> {
                flushPara()
                flushLists()
            }
            else -> {
                flushLists()
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
    }
    if (inCode && code.isNotEmpty()) out += MdBlock.Code(code.toString().trimEnd())
    flushPara()
    flushLists()
    return out.ifEmpty { listOf(MdBlock.Paragraph(raw.trim())) }
}

internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val regex = Regex(
        """(\*\*[^*]+?\*\*|__[^_]+?__|`[^`]+?`|\*[^*\n]+?\*|\[[^\]]+]\([^)]+\))""",
    )
    var index = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > index) append(text.substring(index, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") || token.startsWith("__") -> {
                val inner = token.removeSurrounding("**").removeSurrounding("__")
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                append(inner)
                pop()
            }
            token.startsWith("`") -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium))
                append(token.removeSurrounding("`"))
                pop()
            }
            token.startsWith("*") -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(token.removeSurrounding("*"))
                pop()
            }
            token.startsWith("[") -> {
                val label = token.substringAfter("[").substringBefore("]")
                pushStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium))
                append(label)
                pop()
            }
            else -> append(token)
        }
        index = match.range.last + 1
    }
    if (index < text.length) append(text.substring(index))
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    val blocks = remember(text) { parseMarkdown(text) }
    val color = if (error) palette.red else palette.text
    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> Text(
                        text = inlineMarkdown(block.text),
                        color = color,
                        fontSize = when (block.level) {
                            1 -> 22.sp
                            2 -> 19.sp
                            else -> 17.sp
                        },
                        fontFamily = style.brandSerif,
                        fontWeight = if (style.isClaude) FontWeight.Medium else FontWeight.SemiBold,
                        letterSpacing = if (style.isClaude) 0.3.sp else 0.sp,
                        lineHeight = 26.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    is MdBlock.Paragraph -> Text(
                        text = inlineMarkdown(block.text),
                        color = color,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    is MdBlock.Bullets -> Column(Modifier.padding(bottom = 6.dp, start = 4.dp)) {
                        block.items.forEach { item ->
                            Text(
                                text = buildAnnotatedString {
                                    append("·  ")
                                    append(inlineMarkdown(item))
                                },
                                color = color,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            )
                        }
                    }
                    is MdBlock.Ordered -> Column(Modifier.padding(bottom = 6.dp, start = 4.dp)) {
                        block.items.forEachIndexed { i, item ->
                            Text(
                                text = buildAnnotatedString {
                                    append("${i + 1}. ")
                                    append(inlineMarkdown(item))
                                },
                                color = color,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            )
                        }
                    }
                    is MdBlock.Code -> Text(
                        text = block.text,
                        color = palette.subtext1,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.mantle)
                            .then(
                                if (style.isClaude) {
                                    Modifier.border(1.dp, palette.surface1, RoundedCornerShape(12.dp))
                                } else Modifier,
                            )
                            .padding(10.dp),
                    )
                    is MdBlock.Quote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (style.isClaude) palette.mauve else palette.lavender),
                            )
                            Text(
                                text = inlineMarkdown(block.text),
                                color = palette.subtext0,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    MdBlock.Rule -> androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = palette.surface1,
                    )
                }
            }
        }
    }
}
