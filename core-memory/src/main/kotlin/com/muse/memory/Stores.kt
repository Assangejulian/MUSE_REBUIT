package com.muse.memory

import android.content.Context
import com.muse.llm.ChatMessage
import com.muse.llm.MuseJson
import com.muse.llm.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

class TextFileStore(context: Context, relativePath: String) {
    private val file = File(context.filesDir, relativePath)

    suspend fun read(): String = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ""
        file.readText(Charsets.UTF_8)
    }

    suspend fun write(op: String, text: String): String = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val incoming = text.trim()
        val next = when (op) {
            "replace" -> incoming
            else -> {
                val current = if (file.exists()) file.readText(Charsets.UTF_8).trim() else ""
                if (current.isEmpty()) incoming else "$current\n$incoming"
            }
        }
        file.writeText(if (next.isBlank()) "" else next + "\n", Charsets.UTF_8)
        "${file.name} 已更新，当前 ${next.length} 字。"
    }
}

class MemoryFileStore(context: Context) {
    private val inner = TextFileStore(context, "personalization/memory.md")
    suspend fun read(): String = inner.read()
    suspend fun write(op: String, text: String): String = inner.write(op, text)
}

class BlocklistStore(context: Context) {
    private val inner = TextFileStore(context, "personalization/blocklist.txt")
    suspend fun read(): String = inner.read()
    suspend fun write(text: String): String = inner.write("replace", text)
}

class SessionRepository(private val db: MuseDatabase) {
    fun observeSessions(): Flow<List<SessionEntity>> = db.sessions().observeAll()

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        db.messages().observe(sessionId)

    suspend fun getOrCreateSession(preferredId: String?): SessionEntity {
        if (preferredId != null) {
            db.sessions().get(preferredId)?.let { return it }
        }
        val latest = db.sessions().list().firstOrNull()
        if (latest != null) return latest
        return newSession()
    }

    suspend fun newSession(): SessionEntity {
        val now = System.currentTimeMillis()
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = "新 Session",
            createdAt = now,
            updatedAt = now,
        )
        db.sessions().upsert(session)
        return session
    }

    suspend fun rename(id: String, title: String) {
        val current = db.sessions().get(id) ?: return
        db.sessions().upsert(current.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun touch(id: String) {
        val current = db.sessions().get(id) ?: return
        db.sessions().upsert(current.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSession(id: String) {
        db.messages().deleteForSession(id)
        db.sessions().delete(id)
    }

    suspend fun listMessages(sessionId: String): List<ChatMessage> =
        db.messages().list(sessionId).map { it.toChatMessage() }

    suspend fun saveMessage(sessionId: String, message: ChatMessage, id: String = UUID.randomUUID().toString()) {
        val ordinal = db.messages().maxOrdinal(sessionId) + 1
        db.messages().upsert(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = message.role,
                content = message.content.orEmpty(),
                reasoning = message.reasoningContent.orEmpty(),
                toolJson = message.toolCalls?.let {
                    MuseJson.encodeToString(ListSerializer(ToolCall.serializer()), it)
                }.orEmpty(),
                toolCallId = message.toolCallId.orEmpty(),
                name = message.name.orEmpty(),
                createdAt = System.currentTimeMillis(),
                ordinal = ordinal,
            ),
        )
        if (message.role == "user") {
            val title = message.content.orEmpty().trim().replace('\n', ' ').take(18)
            if (title.isNotEmpty()) rename(sessionId, title)
        } else {
            touch(sessionId)
        }
    }
}

class ScheduleRepository(private val db: MuseDatabase) {
    fun observe(): Flow<List<ScheduleEntity>> = db.schedules().observeAll()

    suspend fun list(): List<ScheduleEntity> = db.schedules().list()

    suspend fun listEnabled(): List<ScheduleEntity> = db.schedules().listEnabled()

    suspend fun get(id: String): ScheduleEntity? = db.schedules().get(id)

    suspend fun upsert(item: ScheduleEntity) = db.schedules().upsert(item)

    suspend fun delete(id: String) = db.schedules().delete(id)
}

class NoteStore(private val db: MuseDatabase) {
    suspend fun save(title: String, body: String): String {
        val id = UUID.randomUUID().toString()
        db.notes().insert(
            NoteEntity(
                id = id,
                title = title.ifBlank { "未命名" },
                body = body,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return "笔记已保存（$id）。"
    }
}

fun MessageEntity.toChatMessage(): ChatMessage {
    val tools = if (toolJson.isBlank()) {
        null
    } else {
        runCatching {
            MuseJson.decodeFromString(ListSerializer(ToolCall.serializer()), toolJson)
        }.getOrNull()
    }
    return ChatMessage(
        role = role,
        content = content,
        reasoningContent = reasoning.ifBlank { null },
        toolCalls = tools,
        toolCallId = toolCallId.ifBlank { null },
        name = name.ifBlank { null },
    )
}
