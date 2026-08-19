package com.muse.app

import android.app.Application
import com.muse.agent.AgentConfig
import com.muse.agent.AgentEvent
import com.muse.agent.formatScheduleInstant
import com.muse.agent.nextAfterRun
import com.muse.llm.ChatMessage
import com.muse.memory.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

class ScheduleHost(
    private val app: Application,
    private val graph: AppGraph,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mutex = Mutex()

    fun fire(id: String, done: () -> Unit = {}) {
        scope.launch {
            try {
                runJob(id)
            } finally {
                done()
            }
        }
    }

    suspend fun resync() {
        val now = System.currentTimeMillis()
        val jobs = graph.schedules.list()
        jobs.forEach { job ->
            if (!job.enabled) {
                graph.alarms.cancel(job.id)
                return@forEach
            }
            if (job.nextAt in 1 until now - GRACE_MS) {
                val next = nextAfterRun(job.repeat, job.hour, job.minute)
                val stamp = System.currentTimeMillis()
                val updated = if (next == null) {
                    job.copy(enabled = false, lastRunAt = stamp, lastStatus = "错过（$GRACE_LABEL 内未执行）")
                } else {
                    job.copy(nextAt = next, lastRunAt = stamp, lastStatus = "错过上一档，已改到 ${formatScheduleInstant(next)}")
                }
                graph.schedules.upsert(updated)
                graph.alarms.set(updated)
            } else {
                graph.alarms.set(job)
            }
        }
    }

    private suspend fun runJob(id: String) {
        val job = graph.schedules.get(id) ?: return
        if (!job.enabled) return
        val now = System.currentTimeMillis()
        if (job.nextAt - now > 60_000) {
            graph.alarms.set(job)
            return
        }
        if (now - job.nextAt > GRACE_MS) {
            val next = nextAfterRun(job.repeat, job.hour, job.minute)
            val stamp = System.currentTimeMillis()
            val updated = if (next == null) {
                job.copy(enabled = false, lastRunAt = stamp, lastStatus = "错过（太晚）")
            } else {
                job.copy(nextAt = next, lastRunAt = stamp, lastStatus = "错过上一档，已改到 ${formatScheduleInstant(next)}")
            }
            graph.schedules.upsert(updated)
            graph.alarms.set(updated)
            return
        }
        if (!mutex.tryLock()) {
            val deferred = job.copy(nextAt = now + 3 * 60_000, lastStatus = "当时有任务在跑，已顺延 3 分钟")
            graph.schedules.upsert(deferred)
            graph.alarms.set(deferred)
            return
        }
        try {
            val settings = graph.settings.current()
            if (settings.keyForModel().isBlank()) {
                graph.schedules.upsert(job.copy(lastStatus = "没有 API Key"))
                advance(job, "没有 API Key")
                return
            }
            val session = graph.sessions.newSession()
            graph.sessions.rename(session.id, "定时 · ${job.title}".take(24))
            val userText = buildString {
                append("【定时任务 ")
                append(job.title)
                append("】到点自动执行。这不是用户正在盯着屏幕。按提示词做完就 finish。\n\n")
                append(job.prompt)
            }
            graph.sessions.saveMessage(session.id, ChatMessage(role = "user", content = userText))
            val health = graph.actions.deviceHealth()
            graph.sessions.saveMessage(session.id, ChatMessage(role = "system", content = "[device_health]\n$health"))
            val task = job.mode != "chat"
            if (task && graph.overlay.canDraw()) {
                if (settings.floatOnTask) graph.overlay.show(settings.theme)
                else graph.overlay.collapse(settings.theme)
                graph.overlay.update("定时 · ${job.title}", "Thinking")
                (app as? MuseApplication)?.taskHost?.enterTaskMode()
            }
            try {
                graph.agent.run(
                    history = emptyList(),
                    userText = userText,
                    config = AgentConfig(
                        apiKey = settings.keyForModel(),
                        baseUrl = settings.baseUrl,
                        model = settings.model,
                        reasoningEffort = settings.reasoningEffort,
                        thinkingEnabled = settings.thinkingEnabled,
                        maxTokens = settings.maxTokens,
                        toolNames = if (task) null else emptyList(),
                        healthText = health,
                    ),
                ).collect { event ->
                    when (event) {
                        is AgentEvent.ThinkingDelta ->
                            graph.overlay.update(event.text, "Thinking")
                        is AgentEvent.ToolStarted ->
                            graph.overlay.update("", "Tool · ${event.name}")
                        is AgentEvent.TurnMessage ->
                            graph.sessions.saveMessage(session.id, event.message)
                        else -> Unit
                    }
                }
                advance(job, "已执行", session.id)
            } finally {
                graph.overlay.hide()
                (app as? MuseApplication)?.taskHost?.exitTaskMode()
            }
        } catch (t: Throwable) {
            advance(job, "失败：${t.message ?: t::class.java.simpleName}")
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun advance(job: ScheduleEntity, status: String, sessionId: String = job.lastSessionId) {
        val next = nextAfterRun(job.repeat, job.hour, job.minute)
        val updated = if (next == null) {
            job.copy(
                enabled = false,
                lastRunAt = System.currentTimeMillis(),
                lastStatus = status,
                lastSessionId = sessionId,
            )
        } else {
            job.copy(
                nextAt = next,
                lastRunAt = System.currentTimeMillis(),
                lastStatus = status,
                lastSessionId = sessionId,
            )
        }
        graph.schedules.upsert(updated)
        graph.alarms.set(updated)
    }

    companion object {
        private const val GRACE_MS = 90 * 60 * 1000L
        private const val GRACE_LABEL = "90 分钟"
    }
}

fun newScheduleId(): String = "s" + UUID.randomUUID().toString().replace("-", "").take(8)
