package com.muse.agent

import com.muse.llm.ToolDefinition
import com.muse.llm.buildEnumProp
import com.muse.llm.buildProps
import com.muse.llm.toolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val MAX_TOOL_ROUNDS = 8
const val TOOL_TIMEOUT_MS = 20_000L
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
        name = "web_search",
        description = "Search the public web and return titles, URLs, and snippets. Use this instead of fetching Google/Baidu/Bing result pages.",
        properties = buildProps("query" to "Search query in the user's language"),
        required = listOf("query"),
    ),
    toolSchema(
        name = "http_fetch",
        description = "HTTP GET a specific public https page and return plain text. Never use this on search engine result pages.",
        properties = buildProps("url" to "Public https URL of one article or page"),
        required = listOf("url"),
    ),
    toolSchema(
        name = "open_url",
        description = "Open a public https URL in the phone browser. Use after the user asks to open a link.",
        properties = buildProps("url" to "Public https URL"),
        required = listOf("url"),
    ),
    toolSchema(
        name = "share_text",
        description = "Open the Android share sheet with text so the user can send it to another app.",
        properties = buildProps("text" to "Text to share"),
        required = listOf("text"),
    ),
    toolSchema(
        name = "open_app",
        description = "Launch an installed app by its display name or package name. Does not tap inside the app.",
        properties = buildProps("name" to "App label or package, e.g. 微信 or com.tencent.mm"),
        required = listOf("name"),
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
Use web_search for current facts, news, prices, or anything you do not know.
After web_search, use http_fetch on one promising URL if you need the page body.
Never http_fetch Google/Baidu/Bing/DuckDuckGo result pages.
Use open_url to open a link in the browser, share_text to share, open_app to launch an installed app.
You cannot tap inside other apps.
Use note_save when the user asks to keep a note.
Call finish when a multi-step task is complete.
"""
