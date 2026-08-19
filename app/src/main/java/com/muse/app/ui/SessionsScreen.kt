package com.muse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import com.muse.design.LocalPalette
import com.muse.design.LocalMuseStyle
import com.muse.memory.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    sessions: List<SessionEntity>,
    currentId: String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit = {},
) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    var pendingDelete by remember { mutableStateOf<SessionEntity?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.crust)
            .statusBarsPadding(),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Sessions",
                    color = palette.text,
                    fontFamily = style.brandSerif,
                    fontWeight = if (style.isClaude) FontWeight.Medium else FontWeight.SemiBold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = palette.text)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.crust),
        )
        SessionList(
            sessions = sessions,
            currentId = currentId,
            onOpen = onOpen,
            onAskDelete = { pendingDelete = it },
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        )
        pendingDelete?.let { session ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                containerColor = palette.base,
                title = { Text("删除这条对话？", color = palette.text) },
                text = { Text("「${session.title}」会从本机删掉。", color = palette.subtext0) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDelete(session.id)
                            pendingDelete = null
                        },
                    ) { Text("删除", color = palette.red) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消", color = palette.subtext0) }
                },
            )
        }
    }
}

@Composable
fun SessionList(
    sessions: List<SessionEntity>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onAskDelete: (SessionEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    if (sessions.isEmpty()) {
        Text("还没有 Session。", color = palette.subtext0, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(modifier = modifier) {
        items(sessions, key = { it.id }) { session ->
            val selected = session.id == currentId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) palette.surface0 else palette.base)
                    .then(
                        if (style.isClaude) {
                            Modifier.border(1.dp, palette.surface1.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                        } else Modifier,
                    )
                    .clickable { onOpen(session.id) }
                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(session.title, color = palette.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    Text(fmt.format(Date(session.updatedAt)), color = palette.overlay1, fontSize = 12.sp)
                }
                IconButton(onClick = { onAskDelete(session) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除对话", tint = palette.overlay1)
                }
            }
        }
    }
}
