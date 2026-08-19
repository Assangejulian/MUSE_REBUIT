package com.muse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.app.update.UpdateState
import com.muse.design.LocalPalette
import com.muse.llm.DEFAULT_MAX_TOKENS
import com.muse.llm.MAX_TOKENS_CAP
import com.muse.llm.MODEL_CATALOG
import com.muse.llm.ModelProvider
import com.muse.llm.modelOption
import com.muse.llm.modelProvider
import com.muse.memory.MuseSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: MuseSettings,
    memoryText: String,
    blocklistText: String = "",
    onBack: () -> Unit,
    onChange: ((MuseSettings) -> MuseSettings) -> Unit,
    onSaveMemory: (String) -> Unit,
    onLoadMemory: suspend () -> String,
    onImportMemoryFile: () -> Unit = {},
    onSaveBlocklist: (String) -> Unit = {},
    onLoadBlocklist: suspend () -> String = { "" },
    update: UpdateState,
    currentVersion: String,
    repoLabel: String,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    updateHint: String? = null,
    shizukuLine: String = "",
    a11yLine: String = "",
    overlayReady: Boolean = false,
    onRequestShizuku: () -> String = { "" },
    onRefreshShizuku: () -> Unit = {},
    onRequestOverlay: () -> Unit = {},
    onRequestA11y: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
) {
    val palette = LocalPalette.current
    var keyDrafts by remember {
        mutableStateOf(ModelProvider.entries.associate { it.name to settings.keyForProvider(it) })
    }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var maxTokens by remember(settings.maxTokens) { mutableStateOf(settings.maxTokens.toString()) }
    var memory by remember { mutableStateOf(memoryText) }
    var blocklist by remember { mutableStateOf(blocklistText) }
    var shizukuHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        memory = onLoadMemory()
        blocklist = onLoadBlocklist()
        onRefreshShizuku()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.crust)
            .statusBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("设置", color = palette.text) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = palette.text)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.crust),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            SectionLabel("更新")
            Text("当前版本 $currentVersion", color = palette.text, fontSize = 15.sp)
            Text("来源 github.com/$repoLabel", color = palette.subtext0, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            UpdateStatus(update)
            if (updateHint != null) {
                Spacer(Modifier.height(6.dp))
                Text(updateHint, color = palette.peach, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCheckUpdate,
                    enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.mauve, contentColor = palette.base),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("检查更新") }
                when (update) {
                    is UpdateState.Available -> Button(
                        onClick = onDownloadUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.green, contentColor = palette.crust),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("下载 ${update.release.version}") }
                    is UpdateState.Downloading -> { }
                    is UpdateState.Ready -> Button(
                        onClick = onInstallUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.green, contentColor = palette.crust),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("安装") }
                    else -> { }
                }
            }
            Spacer(Modifier.height(24.dp))
            SectionLabel("厂商")
            val provider = modelProvider(settings.model)
            ProviderDropdown(
                selected = provider,
                onSelect = { p ->
                    val first = MODEL_CATALOG.first { it.provider == p }
                    onChange { it.withModel(first.id) }
                    baseUrl = modelOption(first.id).defaultBase
                },
            )
            Spacer(Modifier.height(12.dp))
            SectionLabel("Model")
            ChipRow(
                options = MODEL_CATALOG.filter { it.provider == provider }.map { it.id to it.label },
                selected = settings.model,
                onSelect = { value ->
                    onChange { it.withModel(value) }
                    baseUrl = modelOption(value).defaultBase
                },
            )
            Spacer(Modifier.height(16.dp))
            if (provider == ModelProvider.DeepSeek) {
                SectionLabel("Thinking effort")
                ChipRow(
                    options = listOf("low" to "low", "high" to "high", "max" to "max"),
                    selected = settings.reasoningEffort,
                    onSelect = { value -> onChange { it.copy(reasoningEffort = value) } },
                )
                Spacer(Modifier.height(16.dp))
            }
            SectionLabel("主题")
            ChipRow(
                options = listOf(
                    "claude" to "Claude 暖沙",
                    "claude_dark" to "Claude 暗夜",
                    "cream" to "Cream",
                    "system" to "跟随系统",
                    "mocha" to "Mocha",
                    "latte" to "Latte",
                ),
                selected = settings.theme,
                onSelect = { value -> onChange { it.copy(theme = value) } },
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel("定时任务")
            Text("对话里让 Muse 写入，或打开清单手建。到点按提示词跑；页面上的领取时间用一次性补跑。", color = palette.subtext0, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenSchedules,
                colors = ButtonDefaults.buttonColors(containerColor = palette.mauve, contentColor = palette.base),
                shape = RoundedCornerShape(14.dp),
            ) { Text("打开定时清单") }
            Spacer(Modifier.height(16.dp))
            SectionLabel("任务悬浮窗")
            ChipRow(
                options = listOf("on" to "开启", "off" to "关闭"),
                selected = if (settings.floatOnTask) "on" else "off",
                onSelect = { value -> onChange { it.copy(floatOnTask = value == "on") } },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (overlayReady) "关闭时收成小球，点一下可再打开。" else "悬浮窗权限：未授予（任务时无法出浮窗/小球）",
                color = if (overlayReady) palette.green else palette.peach,
                fontSize = 13.sp,
            )
            if (!overlayReady) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRequestOverlay,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.lavender, contentColor = palette.base),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("去开启悬浮窗权限") }
            }
            Spacer(Modifier.height(16.dp))
            SectionLabel("Accessibility")
            Text(a11yLine.ifBlank { "未检测" }, color = palette.subtext0, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRequestA11y,
                colors = ButtonDefaults.buttonColors(containerColor = palette.mauve, contentColor = palette.crust),
                shape = RoundedCornerShape(14.dp),
            ) { Text("去打开无障碍（点节点用这个）") }
            Spacer(Modifier.height(16.dp))
            SectionLabel("Shizuku")
            Text(shizukuLine.ifBlank { "未检测" }, color = palette.subtext0, fontSize = 13.sp)
            if (shizukuHint != null) {
                Text(shizukuHint!!, color = palette.peach, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { shizukuHint = onRequestShizuku() },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.mauve, contentColor = palette.base),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("连接 / 授权") }
                Button(
                    onClick = onRefreshShizuku,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.surface0, contentColor = palette.text),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("刷新") }
            }
            Spacer(Modifier.height(16.dp))
            SectionLabel("max_tokens")
            MuseField(
                value = maxTokens,
                onValueChange = {
                    maxTokens = it
                    it.toIntOrNull()?.let { n ->
                        onChange { s -> s.copy(maxTokens = n.coerceIn(256, MAX_TOKENS_CAP)) }
                    }
                },
                placeholder = DEFAULT_MAX_TOKENS.toString(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel("${provider.label} API Key")
            MuseField(
                value = keyDrafts[provider.name].orEmpty(),
                onValueChange = { next ->
                    keyDrafts = keyDrafts + (provider.name to next)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            SectionLabel("Base URL")
            MuseField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onChange { current ->
                        var next = current.copy(baseUrl = baseUrl.trim().ifBlank { current.baseUrl })
                        ModelProvider.entries.forEach { p ->
                            next = next.withProviderKey(p, keyDrafts[p.name].orEmpty())
                        }
                        next
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = palette.mauve, contentColor = palette.crust),
                shape = RoundedCornerShape(14.dp),
            ) { Text("保存 API") }
            Spacer(Modifier.height(24.dp))
            SectionLabel("memory.md")
            MuseField(
                value = memory,
                onValueChange = { memory = it },
                singleLine = false,
                placeholder = "跨 Session 的偏好，一行一条。",
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSaveMemory(memory) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.lavender, contentColor = palette.base),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("保存 memory") }
                Button(
                    onClick = onImportMemoryFile,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.surface0, contentColor = palette.text),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("从文件导入") }
            }
            Spacer(Modifier.height(16.dp))
            SectionLabel("blocklist.txt")
            MuseField(
                value = blocklist,
                onValueChange = { blocklist = it },
                singleLine = false,
                placeholder = "一行一条。命中当前界面文字则拒绝点击。留空=不拦截。",
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSaveBlocklist(blocklist) },
                colors = ButtonDefaults.buttonColors(containerColor = palette.lavender, contentColor = palette.crust),
                shape = RoundedCornerShape(14.dp),
            ) { Text("保存 blocklist") }
            Spacer(Modifier.height(16.dp))
            Text(
                "专业名词保持英文：Agent / Tool / Model / Token / Thinking。",
                color = palette.overlay1,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun UpdateStatus(update: UpdateState) {
    val palette = LocalPalette.current
    when (update) {
        UpdateState.Idle -> Text("启动时会检查 GitHub Release。", color = palette.overlay1, fontSize = 13.sp)
        UpdateState.Checking -> Text("正在检查…", color = palette.lavender, fontSize = 13.sp)
        is UpdateState.UpToDate -> Text(
            update.detail ?: "已是最新。",
            color = palette.green,
            fontSize = 13.sp,
        )
        is UpdateState.Available -> Text(
            "发现 ${update.release.tag}" + update.release.notes.take(120).let { if (it.isBlank()) "" else "：$it" },
            color = palette.peach,
            fontSize = 13.sp,
        )
        is UpdateState.Downloading -> {
            val total = update.total.takeIf { it > 0 } ?: 1L
            val progress = (update.received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            Column {
                Text(
                    "正在下载 ${update.release.apkName}（${update.received / 1024} KB）",
                    color = palette.lavender,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.mauve,
                    trackColor = palette.surface0,
                )
            }
        }
        is UpdateState.Ready -> Text("已下载 ${update.release.apkName}，可以安装。", color = palette.green, fontSize = 13.sp)
        is UpdateState.Failed -> Text(update.message, color = palette.red, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val palette = LocalPalette.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.mauve,
                    selectedLabelColor = palette.base,
                    containerColor = palette.base,
                    labelColor = palette.text,
                ),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}
