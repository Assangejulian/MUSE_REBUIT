package com.muse.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.agent.formatScheduleInstant
import com.muse.design.LocalPalette
import com.muse.memory.ScheduleEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    jobs: List<ScheduleEntity>,
    exactAlarm: Boolean,
    onBack: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onAdd: suspend (title: String, prompt: String, mode: String, `when`: String, repeat: String) -> String,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var `when` by remember { mutableStateOf("08:00") }
    var mode by remember { mutableStateOf("task") }
    var repeat by remember { mutableStateOf("daily") }
    var hint by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.crust)
            .statusBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("定时任务", color = palette.text) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = palette.text)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.crust),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!exactAlarm && Build.VERSION.SDK_INT >= 31) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.surface0)
                            .padding(14.dp),
                    ) {
                        Text("精确闹钟未开，定时可能晚点。", color = palette.peach, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onRequestExactAlarm,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.mauve, contentColor = palette.base),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("去系统设置允许") }
                    }
                }
            }
            item {
                Text("清单", color = palette.subtext0, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            if (jobs.isEmpty()) {
                item {
                    Text("还没有定时。下面写一条，或在对话里让 Muse 写入。", color = palette.subtext0, fontSize = 14.sp)
                }
            }
            items(jobs, key = { it.id }) { job ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.base)
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(job.title, color = palette.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                            Text(
                                "${if (job.repeat == "daily") "每天" else "一次"} · ${if (job.mode == "chat") "聊天" else "任务"} · ${job.id}",
                                color = palette.overlay1,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = job.enabled,
                            onCheckedChange = { onToggle(job.id, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = palette.mauve),
                        )
                        IconButton(onClick = { onDelete(job.id) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = palette.overlay1)
                        }
                    }
                    Text(
                        "下次 ${if (job.nextAt > 0) formatScheduleInstant(job.nextAt) else "-"}",
                        color = palette.subtext0,
                        fontSize = 13.sp,
                    )
                    if (job.lastStatus.isNotBlank()) {
                        Text(job.lastStatus, color = palette.overlay1, fontSize = 12.sp)
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("新建", color = palette.subtext0, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.base)
                        .padding(14.dp),
                ) {
                    SectionLabel("标题")
                    MuseField(value = title, onValueChange = { title = it }, placeholder = "每天早间检查", modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("提示词")
                    MuseField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        singleLine = false,
                        placeholder = "打开目标 App。能领就领；若页面写着几点可领，用 schedule_task once 挂今天那个时间再去。",
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("时间")
                    MuseField(
                        value = `when`,
                        onValueChange = { `when` = it },
                        placeholder = "08:00 或 today 14:32",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("daily" to "每天", "once" to "一次").forEach { (id, label) ->
                            FilterChip(
                                selected = repeat == id,
                                onClick = { repeat = id },
                                label = { Text(label) },
                                colors = chipColors(repeat == id),
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("task" to "任务", "chat" to "聊天").forEach { (id, label) ->
                            FilterChip(
                                selected = mode == id,
                                onClick = { mode = id },
                                label = { Text(label) },
                                colors = chipColors(mode == id),
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                    if (hint != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(hint!!, color = if (hint!!.startsWith("错误")) LocalPalette.current.red else LocalPalette.current.green, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val result = onAdd(title, prompt, mode, `when`, repeat)
                                hint = result
                                if (!result.startsWith("错误")) {
                                    title = ""
                                    prompt = ""
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.mauve, contentColor = palette.base),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("写入清单") }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun chipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = LocalPalette.current.mauve,
    selectedLabelColor = LocalPalette.current.base,
    containerColor = LocalPalette.current.surface0,
    labelColor = LocalPalette.current.text,
)
