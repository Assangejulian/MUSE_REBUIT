package com.muse.agent

import com.muse.llm.ToolDefinition
import com.muse.llm.buildEnumProp
import com.muse.llm.buildProps
import com.muse.llm.toolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val MAX_TOOL_ROUNDS = 16
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
        description = "Launch an installed app by its display name or package name.",
        properties = buildProps("name" to "App label or package, e.g. 微信 or com.tencent.mm"),
        required = listOf("name"),
    ),
    toolSchema(
        name = "shizuku_status",
        description = "Check whether Shizuku is running and authorized. Required before tap/type/ui_dump/shell.",
    ),
    toolSchema(
        name = "ui_dump",
        description = "Dump the current foreground UI via uiautomator (Shizuku). Returns clickable nodes with bounds. Call this before tap.",
    ),
    toolSchema(
        name = "tap",
        description = "Tap a screen point via Shizuku input tap. Use bounds center from ui_dump.",
        properties = buildJsonObject {
            put("x", buildJsonObject { put("type", "integer"); put("description", "X pixel") })
            put("y", buildJsonObject { put("type", "integer"); put("description", "Y pixel") })
        },
        required = listOf("x", "y"),
    ),
    toolSchema(
        name = "swipe",
        description = "Swipe via Shizuku input swipe.",
        properties = buildJsonObject {
            put("x1", buildJsonObject { put("type", "integer"); put("description", "start X") })
            put("y1", buildJsonObject { put("type", "integer"); put("description", "start Y") })
            put("x2", buildJsonObject { put("type", "integer"); put("description", "end X") })
            put("y2", buildJsonObject { put("type", "integer"); put("description", "end Y") })
        },
        required = listOf("x1", "y1", "x2", "y2"),
    ),
    toolSchema(
        name = "type_text",
        description = "Type into the focused field. ASCII uses input text; Chinese is pasted via clipboard.",
        properties = buildProps("text" to "Text to type"),
        required = listOf("text"),
    ),
    toolSchema(
        name = "keyevent",
        description = "Press a key: BACK, HOME, ENTER, RECENTS, DELETE, PASTE.",
        properties = buildProps("name" to "Key name"),
        required = listOf("name"),
    ),
    toolSchema(
        name = "shell",
        description = "Run a bounded shell command through Shizuku (am/pm/dumpsys/input/cmd/uiautomator). Dangerous commands are blocked.",
        properties = buildProps("command" to "Shell command, e.g. dumpsys activity top"),
        required = listOf("command"),
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
- Never request, repeat, or log the API Key.
- Prefer concise answers. Think in Chinese (short CoT).
- UI language is Chinese; keep professional terms in English (Agent, Tool, Model, Token, Thinking, API Key, Session, Shizuku).
- Reply in Chinese unless the user writes in another language.
- Do not tap payment, password, verification-code, or permission-grant screens. Call finish and tell the user.

When the user states a lasting preference, call memory_write.
Use device_status for time, battery, timezone, or network.
Use web_search for current facts. Never http_fetch search result pages.
If DeepSeek chat works, the phone has internet.
Device control uses Shizuku:
1. shizuku_status if unsure
2. open_app or am start to leave Muse
3. ui_dump to see nodes and bounds
4. tap the center of a bounds box, type_text, swipe, or keyevent BACK/HOME
5. ui_dump again after each mutating action
Do not invent coordinates. Read them from ui_dump.
Use note_save when the user asks to keep a note.
Call finish when a multi-step task is complete.
"""
