package com.muse.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muse.app.ui.ChatScreen
import com.muse.app.ui.OnboardingScreen
import com.muse.app.ui.SessionsScreen
import com.muse.app.ui.SettingsScreen
import com.muse.design.MuseTheme
import com.muse.design.MuseThemeMode
class MainActivity : ComponentActivity(), TaskHost {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as MuseApplication).taskHost = this
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val themeMode = when (state.settings.theme) {
                "latte" -> MuseThemeMode.Latte
                "system" -> MuseThemeMode.System
                else -> MuseThemeMode.Mocha
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
                                update = state.update,
                                currentVersion = viewModel.currentVersion(),
                                repoLabel = viewModel.updateRepoLabel(),
                                onCheckUpdate = viewModel::checkUpdate,
                                onDownloadUpdate = viewModel::downloadUpdate,
                                onInstallUpdate = { viewModel.installUpdate(context) },
                                updateHint = state.updateHint,
                                shizukuLine = state.shizukuLine,
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
}
