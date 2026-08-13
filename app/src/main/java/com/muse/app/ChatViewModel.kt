package com.muse.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muse.agent.AgentConfig
import com.muse.agent.AgentEvent
import com.muse.agent.UiSafety
import com.muse.app.update.UpdateState
import com.muse.llm.ChatMessage
import com.muse.llm.MODEL_FLASH
import com.muse.llm.MODEL_PRO
import com.muse.memory.MuseSettings
import com.muse.memory.SessionEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class UiTool(
    val name: String,
    val args: String,
    val result: String = "",
    val done: Boolean = false,
    val ok: Boolean = true,
)

data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val thinking: String = "",
    val tools: List<UiTool> = emptyList(),
    val streaming: Boolean = false,
    val error: Boolean = false,
)

data class ChatUiState(
    val ready: Boolean = false,
    val hasKey: Boolean = false,
    val session: SessionEntity? = null,
    val sessions: List<SessionEntity> = emptyList(),
    val messages: List<UiMessage> = emptyList(),
    val input: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val settings: MuseSettings = MuseSettings(),
    val update: UpdateState = UpdateState.Idle,
    val updateNotice: String? = null,
    val updateHint: String? = null,
    val shizukuLine: String = "",
    val a11yLine: String = "",
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as MuseApplication).graph
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var runJob: Job? = null
    private var sessionId: String? = null

    init {
        viewModelScope.launch {
            graph.settings.settings.collect { settings ->
                _state.update { it.copy(settings = settings, hasKey = settings.apiKey.isNotBlank()) }
            }
        }
        viewModelScope.launch {
            graph.sessions.observeSessions().collect { list ->
                _state.update { it.copy(sessions = list) }
            }
        }
        viewModelScope.launch { openSession(null) }
        viewModelScope.launch {
            graph.updates.state.collect { update ->
                val notice = (update as? UpdateState.Available)?.let { "发现新版本 ${it.release.version}" }
                _state.update { it.copy(update = update, updateNotice = notice) }
            }
        }
        viewModelScope.launch { graph.updates.check() }
        refreshShizuku()
        graph.overlay.onStop = { stop() }
        viewModelScope.launch { UiSafety.load(graph.blocklist.read()) }
    }

    fun refreshShizuku() {
        val a11yOn = MuseAccessibilityService.enabled(getApplication())
        val a11yLive = MuseAccessibilityService.instance != null
        _state.update {
            it.copy(
                shizukuLine = graph.shizuku.statusLine().replace("\n", " · "),
                a11yLine = "accessibility_enabled=$a11yOn · live=$a11yLive",
            )
        }
    }

    fun requestShizuku(): String {
        val msg = graph.shizuku.requestPermission()
        refreshShizuku()
        return msg
    }

    fun overlayReady(): Boolean = graph.overlay.canDraw()

    fun checkUpdate() {
        viewModelScope.launch { graph.updates.check() }
    }

    fun downloadUpdate() {
        viewModelScope.launch { graph.updates.download() }
    }

    fun installUpdate(context: Context) {
        val hint = graph.updates.install(context)
        _state.update { it.copy(updateHint = hint) }
    }

    fun updateRepoLabel(): String = graph.updates.repoLabel

    fun currentVersion(): String = graph.updates.currentVersion

    fun openSession(id: String?) {
        viewModelScope.launch {
            val session = graph.sessions.getOrCreateSession(id)
            sessionId = session.id
            val stored = graph.sessions.listMessages(session.id)
            _state.update {
                it.copy(
                    ready = true,
                    session = session,
                    messages = foldMessagesForUi(stored),
                    error = null,
                )
            }
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val session = graph.sessions.newSession()
            sessionId = session.id
            _state.update {
                it.copy(session = session, messages = emptyList(), error = null, input = "")
            }
        }
    }

    fun onInput(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun saveOnboarding(apiKey: String, model: String, baseUrl: String) {
        graph.settings.update {
            it.copy(
                apiKey = apiKey.trim(),
                model = model,
                baseUrl = baseUrl.trim().ifBlank { it.baseUrl },
            )
        }
    }

    fun updateSettings(transform: (MuseSettings) -> MuseSettings) {
        graph.settings.update(transform)
    }

    suspend fun readMemory(): String = graph.memoryFiles.read()

    fun saveMemory(text: String) {
        viewModelScope.launch {
            graph.memoryFiles.write("replace", text)
        }
    }

    suspend fun readBlocklist(): String = graph.blocklist.read()

    fun saveBlocklist(text: String) {
        viewModelScope.launch {
            graph.blocklist.write(text)
            UiSafety.load(text)
        }
    }

    fun toggleModel() {
        graph.settings.update {
            it.copy(model = if (it.model == MODEL_PRO) MODEL_FLASH else MODEL_PRO)
        }
    }

    fun send() {
        val text = _state.value.input.trim()
        val sid = sessionId ?: return
        if (text.isEmpty() || _state.value.running) return
        if (!_state.value.hasKey) {
            _state.update { it.copy(error = "还没有填写 API Key。") }
            return
        }
        _state.update { it.copy(input = "", error = null) }
        runJob?.cancel()
        runJob = viewModelScope.launch {
            val userMsg = ChatMessage(role = "user", content = text)
            graph.sessions.saveMessage(sid, userMsg)
            val assistantId = UUID.randomUUID().toString()
            _state.update {
                it.copy(
                    running = true,
                    messages = it.messages + userMsg.toUi() + UiMessage(
                        id = assistantId,
                        role = "assistant",
                        content = "",
                        streaming = true,
                    ),
                )
            }
            val history = graph.sessions.listMessages(sid).dropLastWhile { it.role == "user" && it.content == text }
            val settings = graph.settings.current()
            AgentService.start(getApplication())
            val floating = settings.floatOnTask
            if (floating && graph.overlay.canDraw()) {
                graph.overlay.show()
                graph.overlay.update("开始任务…", "Thinking")
                (getApplication() as MuseApplication).taskHost?.enterTaskMode()
            }
            try {
                graph.agent.run(
                    history = history,
                    userText = text,
                    config = AgentConfig(
                        apiKey = settings.apiKey,
                        baseUrl = settings.baseUrl,
                        model = settings.model,
                        reasoningEffort = settings.reasoningEffort,
                        thinkingEnabled = settings.thinkingEnabled,
                        maxTokens = settings.maxTokens,
                    ),
                ).collect { event ->
                    when (event) {
                        is AgentEvent.ThinkingDelta -> {
                            patchAssistant(assistantId) {
                                it.copy(thinking = it.thinking + event.text)
                            }
                            val think = _state.value.messages.lastOrNull { msg -> msg.id == assistantId }?.thinking.orEmpty()
                            graph.overlay.update(think, "Thinking")
                        }
                        is AgentEvent.ContentDelta -> patchAssistant(assistantId) {
                            it.copy(content = it.content + event.text)
                        }
                        is AgentEvent.ToolStarted -> {
                            patchAssistant(assistantId) {
                                it.copy(tools = it.tools + UiTool(event.name, event.args))
                            }
                            val think = _state.value.messages.lastOrNull { msg -> msg.id == assistantId }?.thinking.orEmpty()
                            graph.overlay.update(think, "Tool · ${event.name}")
                        }
                        is AgentEvent.ToolFinished -> patchAssistant(assistantId) {
                            val tools = it.tools.toMutableList()
                            val idx = tools.indexOfLast { tool -> tool.name == event.name && !tool.done }
                            if (idx >= 0) {
                                tools[idx] = tools[idx].copy(
                                    result = event.result,
                                    done = true,
                                    ok = event.ok,
                                )
                            }
                            it.copy(tools = tools)
                        }
                        is AgentEvent.TurnMessage -> {
                            graph.sessions.saveMessage(sid, event.message)
                        }
                        is AgentEvent.Failed -> {
                            patchAssistant(assistantId) {
                                it.copy(
                                    streaming = false,
                                    error = true,
                                    content = it.content.ifBlank { event.message },
                                )
                            }
                            _state.update { it.copy(error = event.message, running = false) }
                        }
                        is AgentEvent.Completed -> {
                            patchAssistant(assistantId) { it.copy(streaming = false) }
                            _state.update { it.copy(running = false) }
                        }
                    }
                }
            } finally {
                AgentService.stop(getApplication())
                graph.overlay.hide()
                (getApplication() as MuseApplication).taskHost?.exitTaskMode()
                _state.update { it.copy(running = false) }
                patchAssistant(assistantId) { it.copy(streaming = false) }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        AgentService.stop(getApplication())
        graph.overlay.hide()
        (getApplication() as MuseApplication).taskHost?.exitTaskMode()
        _state.update { current ->
            current.copy(
                running = false,
                messages = current.messages.map { msg ->
                    if (msg.streaming) msg.copy(streaming = false, content = msg.content.ifBlank { "已停止。" }) else msg
                },
            )
        }
        val sid = sessionId ?: return
        viewModelScope.launch { closeOpenTurn(sid, "已停止。") }
    }

    private suspend fun closeOpenTurn(sessionId: String, reason: String) {
        val last = graph.sessions.listMessages(sessionId).lastOrNull() ?: return
        if (last.role == "tool" || last.toolCalls != null) {
            graph.sessions.saveMessage(sessionId, ChatMessage(role = "assistant", content = reason))
        }
    }

    private fun patchAssistant(id: String, transform: (UiMessage) -> UiMessage) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == id) transform(it) else it },
            )
        }
    }
}

internal fun foldMessagesForUi(messages: List<ChatMessage>): List<UiMessage> {
    val out = ArrayList<UiMessage>()
    var pending: UiMessage? = null
    for (msg in messages) {
        when (msg.role) {
            "user" -> {
                pending?.let { out += it }
                pending = null
                out += msg.toUi()
            }
            "assistant" -> {
                val ui = msg.toUi()
                pending = if (pending == null) {
                    ui
                } else {
                    pending.copy(
                        thinking = listOf(pending.thinking, ui.thinking).filter { it.isNotBlank() }.joinToString("\n"),
                        content = ui.content.ifBlank { pending.content },
                        tools = pending.tools + ui.tools,
                        error = pending.error || ui.content.contains("上限") || ui.content.contains("已停止"),
                    )
                }
            }
            "tool" -> {
                val current = pending ?: continue
                val tools = current.tools.toMutableList()
                val idx = tools.indexOfLast { it.name == msg.name && it.result.isEmpty() }
                if (idx >= 0) {
                    tools[idx] = tools[idx].copy(result = msg.content.orEmpty(), done = true)
                } else {
                    tools += UiTool(msg.name.orEmpty(), "", msg.content.orEmpty(), done = true)
                }
                pending = current.copy(tools = tools)
            }
        }
    }
    pending?.let { out += it }
    return out
}

private fun ChatMessage.toUi(): UiMessage = UiMessage(
    id = UUID.randomUUID().toString(),
    role = role,
    content = content.orEmpty(),
    thinking = reasoningContent.orEmpty(),
    tools = toolCalls.orEmpty().map { UiTool(it.function.name, it.function.arguments, done = true) },
)
