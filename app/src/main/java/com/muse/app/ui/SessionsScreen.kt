package com.muse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.design.LocalPalette
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
) {
    val palette = LocalPalette.current
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.crust)
            .statusBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Sessions", color = palette.text) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = palette.text)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.crust),
        )
        if (sessions.isEmpty()) {
            Text("还没有 Session。", color = palette.subtext0, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(sessions, key = { it.id }) { session ->
                    val selected = session.id == currentId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) palette.surface0 else palette.base)
                            .clickable { onOpen(session.id) }
                            .padding(14.dp),
                    ) {
                        Text(session.title, color = palette.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(fmt.format(Date(session.updatedAt)), color = palette.overlay1, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
