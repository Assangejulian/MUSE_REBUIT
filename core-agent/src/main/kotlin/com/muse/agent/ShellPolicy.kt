package com.muse.agent

object ShellPolicy {
    private val allowedHeads = setOf(
        "am", "pm", "dumpsys", "input", "cmd", "settings", "getprop",
        "wm", "service", "id", "whoami", "date", "echo", "pwd", "ls",
        "cat", "toybox", "uiautomator", "screencap", "content",
    )

    private val blocked = listOf(
        "rm ", "rm\t", "rm\n", " dd ", "reboot", "format", "mkfs", "wipe",
        " fastboot", " su ", "sudo ", "chmod ", "chown ",
        "pm uninstall", "pm disable", "pm enable", "pm clear", "pm grant", "pm revoke",
        "pm hide", "pm unhide", "settings put",
        ">/dev/", "> /dev/", "mkfs.", "killall", "kill -9",
        "am bug-report", "recovery",
    )

    fun denyReason(raw: String): String? {
        val command = raw.trim()
        if (command.isEmpty()) return "命令为空。"
        if (command.length > 500) return "命令过长。"
        val lower = command.lowercase()
        blocked.firstOrNull { lower.contains(it) }?.let {
            return "命令被安全策略拦截（$it）。"
        }
        val head = command.split(Regex("\\s+"), limit = 2).first().lowercase()
        if (head !in allowedHeads) {
            return "只允许这些命令头：${allowedHeads.joinToString(" ")}"
        }
        if (head == "pm" && lower.contains(" uninstall")) return "禁止 pm uninstall。"
        if (head == "settings" && lower.contains(" put ")) return "禁止 settings put。"
        return null
    }
}

fun compactUiDump(xml: String, limit: Int = 40): String {
    val nodeRe = Regex("<node\\b([^>]*)>", RegexOption.IGNORE_CASE)
    val attr = { blob: String, name: String ->
        Regex("""$name="([^"]*)"""").find(blob)?.groupValues?.get(1).orEmpty()
    }
    val lines = ArrayList<String>()
    for (match in nodeRe.findAll(xml)) {
        val blob = match.groupValues[1]
        val text = unescapeXml(attr(blob, "text"))
        val desc = unescapeXml(attr(blob, "content-desc"))
        val clickable = attr(blob, "clickable") == "true"
        val bounds = attr(blob, "bounds")
        if (text.isBlank() && desc.isBlank() && !clickable) continue
        val cls = attr(blob, "class").substringAfterLast('.')
        val label = text.ifBlank { desc }.ifBlank { cls.ifBlank { "node" } }
        val flag = if (clickable) "*" else "-"
        lines += "$flag $label  $bounds"
        if (lines.size >= limit) break
    }
    return if (lines.isEmpty()) "(没有可识别节点)" else lines.joinToString("\n")
}

private fun unescapeXml(value: String): String =
    value.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#10;", " ")
