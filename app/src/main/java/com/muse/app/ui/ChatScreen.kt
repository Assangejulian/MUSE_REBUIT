package com.muse.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.agent.museToolChoices
import com.muse.app.ChatUiState
import com.muse.app.UiMessage
import com.muse.app.UiTool
import com.muse.design.CatppuccinPalette
import com.muse.design.LocalPalette
import com.muse.design.LocalMuseStyle

@OptIn(ExperimentalMaterial3Api::class)
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
    onSetTaskMode: (Boolean) -> Unit = {},
    onToggleExtraTool: (String) -> Unit = {},
    onImportMemory: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
    onShowBall: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var toolsOpen by remember { mutableStateOf(false) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) {
            val cur = state.input
            onInput(if (cur.isBlank()) spoken else "$cur$spoken")
        }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSpeech(context, speech::launch)
        else Toast.makeText(context, "需要麦克风权限才能语音输入。", Toast.LENGTH_SHORT).show()
    }
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
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSessions) {
                Icon(Icons.Outlined.Forum, contentDescription = "Sessions", tint = palette.text)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Muse",
                    color = palette.text,
                    fontWeight = if (style.isClaude) FontWeight.Medium else FontWeight.SemiBold,
                    fontFamily = style.brandSerif,
                    fontSize = if (style.isClaude) 20.sp else 18.sp,
                    letterSpacing = if (style.isClaude) 0.6.sp else 0.sp,
                )
                Text(
                    state.session?.title ?: "新对话",
                    color = palette.subtext0,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (state.running) {
                IconButton(onClick = onShowBall) {
                    Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = "打开小球", tint = palette.mauve)
                }
            }
            ModeChip(
                task = state.settings.taskMode,
                onClick = { toolsOpen = true },
            )
            ModelChip(label = modelLabel(state.settings.model), onClick = onToggleModel)
            IconButton(onClick = onNewSession) {
                Icon(Icons.Outlined.Add, contentDescription = "新对话", tint = palette.text)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = palette.text)
            }
        }

        if (state.updateNotice != null) {
            Text(
                text = "${state.updateNotice}  ·  去设置",
                color = palette.base,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
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
                        body = if (state.settings.taskMode) {
                            "现在是任务模式，Muse 可以使用全部 Tool。"
                        } else {
                            "默认是聊天模式。左侧 + 可以换成任务模式，或只打开某几个 Tool。"
                        },
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
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

        ComposerBar(
            value = state.input,
            running = state.running,
            onValueChange = onInput,
            onPlus = { toolsOpen = true },
            onMic = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) startSpeech(context, speech::launch)
                else micPerm.launch(Manifest.permission.RECORD_AUDIO)
            },
            onSend = onSend,
            onStop = onStop,
        )
    }

    if (toolsOpen) {
        ToolPickerSheet(
            taskMode = state.settings.taskMode,
            extraTools = state.extraTools,
            onDismiss = { toolsOpen = false },
            onSetTaskMode = onSetTaskMode,
            onToggleExtraTool = onToggleExtraTool,
            onImportMemory = {
                toolsOpen = false
                onImportMemory()
            },
            onOpenSchedules = {
                toolsOpen = false
                onOpenSchedules()
            },
            onShowBall = {
                toolsOpen = false
                onShowBall()
            },
        )
    }
}

@Composable
private fun ModeChip(task: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    Text(
        text = if (task) "任务" else "聊天",
        color = if (task) palette.base else palette.mauve,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (task) palette.mauve else palette.base)
            .then(
                if (!task && style.isClaude) {
                    Modifier.border(1.dp, palette.surface1, RoundedCornerShape(999.dp))
                } else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    Text(
        text = label,
        color = if (style.isClaude) palette.mauve else palette.lavender,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(palette.base)
            .then(
                if (style.isClaude) {
                    Modifier.border(1.dp, palette.surface1, RoundedCornerShape(999.dp))
                } else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun ComposerBar(
    value: String,
    running: Boolean,
    onValueChange: (String) -> Unit,
    onPlus: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    val canSend = value.isNotBlank() && !running
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(palette.base)
            .border(1.dp, if (style.isClaude) palette.surface1 else palette.surface0, RoundedCornerShape(26.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.surface0)
                .clickable(onClick = onPlus),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "工具", tint = palette.text)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(color = palette.text, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(palette.mauve),
            maxLines = 6,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text("发给 Muse…", color = palette.overlay0, fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
        if (!running) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(palette.surface0)
                    .clickable(onClick = onMic),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Mic, contentDescription = "语音输入", tint = palette.text)
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(
                    if (running || canSend) {
                        if (style.isClaude) {
                            Modifier.background(style.accentGradient)
                        } else {
                            Modifier.background(palette.mauve)
                        }
                    } else {
                        Modifier.background(palette.surface0)
                    },
                )
                .clickable(enabled = running || canSend) {
                    if (running) onStop() else onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (running) Icons.Outlined.Stop else Icons.Rounded.ArrowUpward,
                contentDescription = if (running) "Stop" else "发送",
                tint = if (running || canSend) palette.base else palette.overlay1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ToolPickerSheet(
    taskMode: Boolean,
    extraTools: Set<String>,
    onDismiss: () -> Unit,
    onSetTaskMode: (Boolean) -> Unit,
    onToggleExtraTool: (String) -> Unit,
    onImportMemory: () -> Unit,
    onOpenSchedules: () -> Unit,
    onShowBall: () -> Unit,
) {
    val palette = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.base,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("模式", color = palette.subtext0, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !taskMode,
                    onClick = { onSetTaskMode(false) },
                    label = { Text("聊天") },
                    colors = sheetChipColors(palette, !taskMode),
                    shape = RoundedCornerShape(14.dp),
                )
                FilterChip(
                    selected = taskMode,
                    onClick = { onSetTaskMode(true) },
                    label = { Text("任务") },
                    colors = sheetChipColors(palette, taskMode),
                    shape = RoundedCornerShape(14.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (taskMode) "任务模式会打开全部 Tool，适合操作手机。" else "聊天模式默认不用 Tool。点下面的名字可以单独打开某几个。",
                color = palette.subtext0,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text("Tool", color = palette.subtext0, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                museToolChoices().forEach { tool ->
                    val on = taskMode || tool.name in extraTools
                    FilterChip(
                        selected = on,
                        onClick = { if (!taskMode) onToggleExtraTool(tool.name) },
                        enabled = !taskMode,
                        label = { Text(tool.label) },
                        colors = sheetChipColors(palette, on),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "打开悬浮小球",
                color = palette.mauve,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onShowBall)
                    .padding(vertical = 8.dp),
            )
            Text(
                "定时任务清单",
                color = palette.mauve,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenSchedules)
                    .padding(vertical = 8.dp),
            )
            Text(
                "导入到 memory.md",
                color = palette.mauve,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onImportMemory)
                    .padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun sheetChipColors(palette: CatppuccinPalette, selected: Boolean) =
    FilterChipDefaults.filterChipColors(
        selectedContainerColor = palette.mauve,
        selectedLabelColor = palette.base,
        containerColor = palette.surface0,
        labelColor = palette.text,
        disabledContainerColor = palette.surface0,
        disabledLabelColor = palette.subtext0,
        disabledSelectedContainerColor = palette.mauve.copy(alpha = 0.55f),
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: UiMessage) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    val context = LocalContext.current
    val mine = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(if (mine) 20.dp else 22.dp))
                .background(if (mine) palette.surface0 else palette.base)
                .then(
                    if (!mine && style.isClaude) {
                        Modifier.border(1.dp, palette.surface1.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                    } else Modifier,
                )
                .then(
                    if (!mine && message.content.isNotBlank()) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { copyText(context, message.content) },
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(14.dp),
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
                if (mine) {
                    Text(message.content, color = palette.text, fontSize = 16.sp, lineHeight = 24.sp)
                } else {
                    MarkdownText(message.content, error = message.error)
                }
            } else if (message.streaming && message.thinking.isBlank()) {
                Text("正在连接 Model…", color = palette.overlay1, fontSize = 14.sp)
            }
            if (!mine && message.content.isNotBlank() && !message.streaming) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制",
                        tint = palette.overlay1,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { copyText(context, message.content) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, live: Boolean) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    var expanded by remember { mutableStateOf(live) }
    LaunchedEffect(live) { if (live) expanded = true }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.mantle)
            .then(
                if (style.isClaude) {
                    Modifier.border(1.dp, palette.surface1, RoundedCornerShape(14.dp))
                } else Modifier,
            )
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Text(
            text = if (live) "正在思考…" else (if (style.isClaude) "Claude Thinking · 思考过程" else "思考过程"),
            color = if (style.isClaude) palette.mauve else palette.lavender,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = if (style.isClaude) 0.3.sp else 0.sp,
        )
        AnimatedVisibility(expanded) {
            Text(
                text = text,
                color = palette.subtext0,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ToolChip(tool: UiTool, palette: CatppuccinPalette) {
    val style = LocalMuseStyle.current
    val color = when {
        !tool.done -> palette.peach
        tool.ok -> palette.green
        else -> palette.red
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.mantle)
            .then(
                if (style.isClaude) {
                    Modifier.border(1.dp, palette.surface1.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                } else Modifier,
            )
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

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("muse", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun startSpeech(context: Context, launch: (Intent) -> Unit) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "说给 Muse")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    if (intent.resolveActivity(context.packageManager) == null) {
        Toast.makeText(context, "这台手机没有语音识别。", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { launch(intent) }.onFailure {
        Toast.makeText(context, "打不开语音识别。", Toast.LENGTH_SHORT).show()
    }
}
