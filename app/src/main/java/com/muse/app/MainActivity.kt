package com.muse.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muse.app.ui.ChatScreen
import com.muse.app.ui.OnboardingScreen
import com.muse.app.ui.ScheduleScreen
import com.muse.app.ui.SessionsScreen
import com.muse.app.ui.SettingsScreen
import com.muse.design.MuseTheme
import com.muse.design.MuseThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), TaskHost {
    private val viewModel: ChatViewModel by viewModels()
    private var memoryReplaceOnImport = false

    private val memoryImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { readImportText(uri) }
            if (text.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, "读不出这个文件，换一个 txt / md 试试。", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.importMemory(text, memoryReplaceOnImport)
                Toast.makeText(this@MainActivity, "已导入 memory.md", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as MuseApplication).taskHost = this
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val themeMode = when (state.settings.theme) {
                "claude" -> MuseThemeMode.ClaudeLight
                "claude_dark" -> MuseThemeMode.ClaudeDark
                "latte" -> MuseThemeMode.Latte
                "mocha" -> MuseThemeMode.Mocha
                "system" -> MuseThemeMode.System
                else -> MuseThemeMode.Cream
            }
            MuseTheme(mode = themeMode) {
                if (!state.hasKey) {
                    OnboardingScreen { key, model, base ->
                        viewModel.saveOnboarding(key, model, base)
                    }
                } else {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "chat") {
                        composable("chat") {
                            ChatScreen(
                                state = state,
                                onInput = viewModel::onInput,
                                onSend = viewModel::send,
                                onStop = viewModel::stop,
                                onNewSession = viewModel::newSession,
                                onOpenSessions = { nav.navigate("sessions") },
                                onOpenSettings = { nav.navigate("settings") },
                                onToggleModel = viewModel::toggleModel,
                                onOpenUpdate = { nav.navigate("settings") },
                                onSetTaskMode = viewModel::setTaskMode,
                                onToggleExtraTool = viewModel::toggleExtraTool,
                                onImportMemory = {
                                    memoryReplaceOnImport = false
                                    memoryImport.launch(arrayOf("text/plain", "text/markdown", "text/*"))
                                },
                                onOpenSchedules = { nav.navigate("schedules") },
                                onShowBall = viewModel::showBall,
                                onOpenReceipt = { id ->
                                    viewModel.openSession(id)
                                },
                            )
                        }
                        composable("sessions") {
                            SessionsScreen(
                                sessions = state.sessions,
                                currentId = state.session?.id,
                                onBack = { nav.popBackStack() },
                                onOpen = { id ->
                                    viewModel.openSession(id)
                                    nav.popBackStack()
                                },
                                onDelete = viewModel::deleteSession,
                            )
                        }
                        composable("settings") {
                            val context = LocalContext.current
                            var memory by remember { mutableStateOf("") }
                            LaunchedEffect(Unit) {
                                memory = viewModel.readMemory()
                            }
                            SettingsScreen(
                                settings = state.settings,
                                memoryText = memory,
                                onBack = { nav.popBackStack() },
                                onChange = viewModel::updateSettings,
                                onSaveMemory = viewModel::saveMemory,
                                onLoadMemory = { viewModel.readMemory() },
                                onImportMemoryFile = {
                                    memoryReplaceOnImport = false
                                    memoryImport.launch(arrayOf("text/plain", "text/markdown", "text/*"))
                                },
                                onSaveBlocklist = viewModel::saveBlocklist,
                                onLoadBlocklist = { viewModel.readBlocklist() },
                                update = state.update,
                                currentVersion = viewModel.currentVersion(),
                                repoLabel = viewModel.updateRepoLabel(),
                                onCheckUpdate = viewModel::checkUpdate,
                                onDownloadUpdate = viewModel::downloadUpdate,
                                onInstallUpdate = { viewModel.installUpdate(context) },
                                updateHint = state.updateHint,
                                shizukuLine = state.shizukuLine,
                                a11yLine = state.a11yLine,
                                overlayReady = viewModel.overlayReady(),
                                onRequestShizuku = { viewModel.requestShizuku() },
                                onRefreshShizuku = viewModel::refreshShizuku,
                                onRequestOverlay = {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName"),
                                        ),
                                    )
                                },
                                onRequestA11y = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onOpenSchedules = { nav.navigate("schedules") },
                            )
                        }
                        composable("schedules") {
                            LaunchedEffect(Unit) { viewModel.refreshExactAlarm() }
                            ScheduleScreen(
                                jobs = state.schedules,
                                exactAlarm = state.exactAlarm,
                                onBack = { nav.popBackStack() },
                                onRequestExactAlarm = {
                                    if (Build.VERSION.SDK_INT >= 31) {
                                        startActivity(
                                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                data = Uri.parse("package:$packageName")
                                            },
                                        )
                                    }
                                },
                                onAdd = { title, prompt, mode, `when`, repeat ->
                                    viewModel.addSchedule(title, prompt, mode, `when`, repeat)
                                },
                                onToggle = viewModel::setScheduleEnabled,
                                onDelete = viewModel::deleteSchedule,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if ((application as MuseApplication).taskHost === this) {
            (application as MuseApplication).taskHost = null
        }
        super.onDestroy()
    }

    override fun enterTaskMode() {
        moveTaskToBack(true)
    }

    override fun exitTaskMode() {
        // User can reopen from notification or recents.
    }

    private fun readImportText(uri: Uri): String? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull()
    }
}
