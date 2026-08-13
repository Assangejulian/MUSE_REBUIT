package com.muse.agent

import com.muse.llm.ToolDefinition
import com.muse.llm.buildEnumProp
import com.muse.llm.buildProps
import com.muse.llm.toolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val MAX_TOOL_ROUNDS = 8
const val TOOL_TIMEOUT_MS = 8_000L
const val TOOL_OUTPUT_LIMIT = 8 * 1024

interface MemoryPort {
    suspend fun read(): String
    suspend fun write(op: String, text: String): String
}

interface NotePort {
    suspend fun save(title: String, body: String): String
}

interface DevicePort {
    suspend fun status(): String
}

interface HttpPort {
    suspend fun fetch(url: String): String
}

fun museToolDefinitions(): List<ToolDefinition> = listOf(
    toolSchema(
        name = "device_status",
        description = "Read the phone's local time, timezone, battery percent, charging state, and network type.",
    ),
    toolSchema(
        name = "memory_read",
        description = "Read the durable user memory.md that should be respected across sessions.",
    ),
    toolSchema(
        name = "memory_write",
        description = "Save a lasting user preference or fact into memory.md. Use for durable preferences only.",
        properties = buildJsonObject {
            put("op", buildEnumProp("append a line or replace the whole file", listOf("append", "replace")))
            put("text", buildProps().let {
                buildJsonObject {
                    put("type", "string")
                    put("description", "The memory text to write. Keep it short and factual.")
                }
            })
        },
        required = listOf("op", "text"),
    ),
    toolSchema(
        name = "note_save",
        description = "Save a note into Muse private storage. Not shared with other apps.",
        properties = buildProps(
            "title" to "Short title",
            "body" to "Note body",
        ),
        required = listOf("title", "body"),
    ),
    toolSchema(
        name = "http_fetch",
        description = "HTTP GET a public https URL and return plain text. Do not use for localhost or private networks.",
        properties = buildProps("url" to "Public https URL"),
        required = listOf("url"),
    ),
    toolSchema(
        name = "finish",
        description = "Mark the current multi-step task complete. Optional if you already answered the user.",
        properties = buildProps("summary" to "One-sentence summary of what was done"),
        required = listOf("summary"),
    ),
)

const val SYSTEM_PROMPT = """You are Muse, a personal Agent running on the user's Android phone.

Capabilities:
- Answer questions and help with everyday tasks.
- Call tools when they improve the answer.
- Remember durable user preferences via memory_write.

Constraints:
- You cannot tap other apps, send SMS, or change system settings.
- Never request, repeat, or log the API Key.
- Prefer concise answers.
- UI language is Chinese; keep professional terms in English (Agent, Tool, Model, Token, Thinking, API Key, Session).
- Reply in Chinese unless the user writes in another language.

When the user states a lasting preference, call memory_write.
Use device_status for time, battery, timezone, or network.
Use http_fetch only for public https URLs the user asked about.
Use note_save when the user asks to keep a note.
Call finish when a multi-step task is complete.
"""
