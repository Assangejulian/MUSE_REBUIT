package com.muse.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.app.ChatUiState
import com.muse.app.UiMessage
import com.muse.app.UiTool
import com.muse.design.CatppuccinPalette
import com.muse.design.LocalPalette

@Composable
fun ChatScreen(
    state: ChatUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleModel: () -> Unit,
    onOpenUpdate: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content, state.messages.lastOrNull()?.thinking) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.crust)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSessions) {
                Icon(Icons.Outlined.Forum, contentDescription = "Sessions", tint = palette.text)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Muse", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(
                    state.session?.title ?: "新 Session",
                    color = palette.subtext0,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            ModelChip(label = modelLabel(state.settings.model), onClick = onToggleModel)
            IconButton(onClick = onNewSession) {
                Icon(Icons.Outlined.Add, contentDescription = "新 Session", tint = palette.text)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = palette.text)
            }
        }

        if (state.updateNotice != null) {
            Text(
                text = "${state.updateNotice}  ·  去设置",
                color = palette.crust,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.mauve)
                    .clickable(onClick = onOpenUpdate)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyHint(
                        title = "今天想做什么？",
                        body = "默认 Model 是 Flash。可以说「记住我喜欢短回复」，或问现在的时间和电量。",
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                }
            }
        }

        if (state.error != null && !state.running) {
            Text(
                state.error,
                color = palette.red,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            MuseField(
                value = state.input,
                onValueChange = onInput,
                placeholder = "发给 Muse…",
                singleLine = false,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            val canSend = state.input.isNotBlank() && !state.running
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (state.running || canSend) palette.mauve else palette.surface0)
                    .clickable(enabled = state.running || canSend) {
                        if (state.running) onStop() else onSend()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.running) Icons.Outlined.Stop else Icons.Rounded.ArrowUpward,
                    contentDescription = if (state.running) "Stop" else "发送",
                    tint = palette.crust,
                )
            }
        }
    }
}

@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        text = label,
        color = palette.mauve,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.base)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val palette = LocalPalette.current
    val mine = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (mine) palette.surface0 else palette.base)
                .padding(12.dp),
        ) {
            if (!mine && message.thinking.isNotBlank()) {
                ThinkingBlock(message.thinking, message.streaming && message.content.isEmpty())
                Spacer(Modifier.height(8.dp))
            }
            if (message.tools.isNotEmpty()) {
                message.tools.forEach { ToolChip(it, palette) }
                Spacer(Modifier.height(8.dp))
            }
            if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    color = if (message.error) palette.red else palette.text,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            } else if (message.streaming && message.thinking.isBlank()) {
                Text("正在连接 Model…", color = palette.overlay1, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, live: Boolean) {
    val palette = LocalPalette.current
    var expanded by remember { mutableStateOf(live) }
    LaunchedEffect(live) { if (live) expanded = true }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.mantle)
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Text(
            if (live) "正在 Thinking" else "Thinking",
            color = palette.lavender,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        AnimatedVisibility(expanded) {
            Text(
                text = text,
                color = palette.overlay2,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ToolChip(tool: UiTool, palette: CatppuccinPalette) {
    val color = when {
        !tool.done -> palette.peach
        tool.ok -> palette.green
        else -> palette.red
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.mantle)
            .padding(8.dp),
    ) {
        Text(
            "Tool · ${tool.name}" + if (!tool.done) " …" else "",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (tool.done && tool.result.isNotBlank()) {
            Text(
                tool.result.take(280),
                color = palette.subtext0,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
