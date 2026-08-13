package com.muse.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muse.agent.AgentConfig
import com.muse.agent.AgentEvent
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
    }

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
                    messages = stored.filter { msg -> msg.role == "user" || msg.role == "assistant" }
                        .map { msg -> msg.toUi() },
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
                        is AgentEvent.ThinkingDelta -> patchAssistant(assistantId) {
                            it.copy(thinking = it.thinking + event.text)
                        }
                        is AgentEvent.ContentDelta -> patchAssistant(assistantId) {
                            it.copy(content = it.content + event.text)
                        }
                        is AgentEvent.ToolStarted -> patchAssistant(assistantId) {
                            it.copy(tools = it.tools + UiTool(event.name, event.args))
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
                            val snapshot = _state.value.messages.lastOrNull { it.id == assistantId }
                            val stored = event.assistant.copy(
                                content = snapshot?.content?.ifBlank { event.assistant.content },
                                reasoningContent = snapshot?.thinking?.ifBlank { event.assistant.reasoningContent },
                            )
                            graph.sessions.saveMessage(sid, stored, assistantId)
                            patchAssistant(assistantId) { it.copy(streaming = false) }
                            _state.update { it.copy(running = false) }
                        }
                    }
                }
            } finally {
                AgentService.stop(getApplication())
                _state.update { it.copy(running = false) }
                patchAssistant(assistantId) { it.copy(streaming = false) }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        AgentService.stop(getApplication())
        _state.update { current ->
            current.copy(
                running = false,
                messages = current.messages.map { msg ->
                    if (msg.streaming) msg.copy(streaming = false, content = msg.content.ifBlank { "已停止。" }) else msg
                },
            )
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

private fun ChatMessage.toUi(): UiMessage = UiMessage(
    id = UUID.randomUUID().toString(),
    role = role,
    content = content.orEmpty(),
    thinking = reasoningContent.orEmpty(),
    tools = toolCalls.orEmpty().map { UiTool(it.function.name, it.function.arguments, done = true) },
)
