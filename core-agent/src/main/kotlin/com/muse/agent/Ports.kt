package com.muse.agent

import com.muse.llm.ToolDefinition
import com.muse.llm.buildEnumProp
import com.muse.llm.buildProps
import com.muse.llm.toolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val MAX_TOOL_ROUNDS = 100
const val TOOL_TIMEOUT_MS = 20_000L
const val TOOL_OUTPUT_LIMIT = 8 * 1024

val OBSERVE_TOOLS = setOf(
    "ui_snapshot",
    "find_nodes",
    "ui_status",
    "shizuku_status",
    "memory_read",
    "ocr_screen",
)

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

fun toolsForRun(toolNames: List<String>?): List<ToolDefinition> {
    val all = museToolDefinitions()
    if (toolNames == null) return all
    if (toolNames.isEmpty()) return emptyList()
    val allow = toolNames.toSet()
    return all.filter { it.function.name in allow }
}

data class ToolChoice(
    val name: String,
    val label: String,
)

fun museToolChoices(): List<ToolChoice> = listOf(
    ToolChoice("web_search", "网页搜索"),
    ToolChoice("http_fetch", "打开网页正文"),
    ToolChoice("open_url", "用浏览器打开"),
    ToolChoice("open_app", "打开应用"),
    ToolChoice("ui_snapshot", "看当前界面"),
    ToolChoice("ocr_screen", "屏幕 OCR"),
    ToolChoice("float_window", "悬浮窗"),
    ToolChoice("click_text", "点文字"),
    ToolChoice("click_node", "点节点"),
    ToolChoice("type_text", "输入文字"),
    ToolChoice("scroll", "滑动"),
    ToolChoice("device_status", "电量与时间"),
    ToolChoice("memory_read", "读 memory"),
    ToolChoice("memory_write", "写 memory"),
    ToolChoice("note_save", "存笔记"),
    ToolChoice("share_text", "系统分享"),
    ToolChoice("shell", "Shizuku shell"),
    ToolChoice("tap", "坐标点击"),
    ToolChoice("ui_dump", "Shizuku dump"),
)

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
        properties = buildProps("name" to "App label or package name"),
        required = listOf("name"),
    ),
    toolSchema(
        name = "ui_status",
        description = "Check Accessibility and Shizuku. Accessibility is preferred for reading/clicking nodes.",
    ),
    toolSchema(
        name = "ui_snapshot",
        description = "Observe the current screen as a compact node list. Prefer this before click_node/click_text. Uses Accessibility when on, else Shizuku dump.",
    ),
    toolSchema(
        name = "ocr_screen",
        description = "OCR the current screen and return visible text with tap centers. Use when the accessibility tree is empty, custom-drawn, or you need to confirm what the user can see. Then tap those coordinates or click_text.",
    ),
    toolSchema(
        name = "float_window",
        description = "Show or hide the floating CoT window. Use off if the overlay covers the app you are controlling; on to bring Thinking back.",
        properties = buildJsonObject {
            put("state", buildEnumProp("on shows the overlay, off hides it", listOf("on", "off")))
        },
        required = listOf("state"),
    ),
    toolSchema(
        name = "find_nodes",
        description = "Search the latest snapshot for text, content-desc, viewId, or node id (n3).",
        properties = buildProps("query" to "Text or node id to find"),
        required = listOf("query"),
    ),
    toolSchema(
        name = "click_node",
        description = "Click a node by id from the last snapshot (e.g. n3). Rematches on a fresh tree. The result already includes a new snapshot.",
        properties = buildProps("id" to "Node id like n3"),
        required = listOf("id"),
    ),
    toolSchema(
        name = "click_text",
        description = "Find a visible node containing this text and click it. The result already includes a new snapshot.",
        properties = buildProps("text" to "Visible text or content-desc"),
        required = listOf("text"),
    ),
    toolSchema(
        name = "scroll",
        description = "Scroll the current screen. Direction: up, down, left, right.",
        properties = buildProps("direction" to "up/down/left/right"),
        required = listOf("direction"),
    ),
    toolSchema(
        name = "wait",
        description = "Wait for the UI to settle, in milliseconds (200-4000).",
        properties = buildJsonObject {
            put("ms", buildJsonObject { put("type", "integer"); put("description", "Milliseconds") })
        },
        required = listOf("ms"),
    ),
    toolSchema(
        name = "shizuku_status",
        description = "Check whether Shizuku is running and authorized.",
    ),
    toolSchema(
        name = "ui_dump",
        description = "Raw uiautomator dump via Shizuku. Use only if ui_snapshot is empty.",
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
- If a Tool refuses a click because of the user's blocklist, stop and tell the user.
- Earlier turns in this Session — including Thinking and Tool results — are already in the message history. Use them. Do not claim a task has not started if those turns exist.

When the user states a lasting preference, call memory_write.
Use device_status for time, battery, timezone, or network.
Use web_search for current facts. Never http_fetch search result pages.
If DeepSeek chat works, the phone has internet.
Device control:
1. ui_status if unsure. Prefer Accessibility over Shizuku tap.
2. open_app to leave Muse
3. ui_snapshot to see nodes (n1, n2…). Do not invent ids.
4. click_node or click_text. The Tool result already includes a fresh snapshot — use those new ids. Do not immediately ui_snapshot again unless the tree looks stale.
5. type_text into the focused field. scroll up/down. keyevent BACK/HOME.
6. tap/swipe/ui_dump only if snapshot has no useful nodes (custom-drawn UI).
7. ocr_screen when the tree is empty, custom-drawn, or you need to double-check visible text. It returns text plus tap centers. Prefer ui_snapshot first — OCR is slower. Use OCR to assist judgment, not as the default look.
8. float_window off if the overlay blocks taps or OCR; float_window on to show Thinking again. The user's setting is updated.
Use note_save when the user asks to keep a note.
Call finish when a multi-step task is complete.
"""
