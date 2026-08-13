package com.muse.agent

data class UiNode(
    val id: String,
    val text: String,
    val desc: String,
    val viewId: String,
    val cls: String,
    val clickable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val cx: Int,
    val cy: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun label(): String = text.ifBlank { desc }.ifBlank { viewId.substringAfterLast('/') }.ifBlank { cls }
    fun kind(): String = when {
        editable -> "输入"
        checkable -> "开关"
        clickable -> "按钮"
        else -> "文本"
    }
}

data class UiSnapshot(
    val pkg: String,
    val title: String,
    val source: String,
    val nodes: List<UiNode>,
)

object UiSafety {
    private val blocked = listOf(
        "支付", "付款", "转账", "密码", "验证码", "短信验证",
        "立即付款", "确认支付", "指纹", "面容", "输入验证码",
        "同意并继续", "允许访问", "授权登录",
        "payment", "password", "verify", "otp",
    )

    fun blockReason(snapshot: UiSnapshot): String? {
        val hay = buildString {
            append(snapshot.title)
            append(' ')
            snapshot.nodes.take(30).forEach { append(it.text).append(' ').append(it.desc).append(' ') }
        }
        val hit = blocked.firstOrNull { hay.contains(it, ignoreCase = true) } ?: return null
        return "安全策略拦截：当前界面像「$hit」。请你自己操作，Agent 不会点下去。"
    }
}

fun formatSnapshot(snapshot: UiSnapshot, limit: Int = 40): String = buildString {
    append("source=").append(snapshot.source)
    append(" pkg=").append(snapshot.pkg.ifBlank { "?" })
    if (snapshot.title.isNotBlank()) append(" title=").append(snapshot.title)
    append('\n')
    val ranked = snapshot.nodes.sortedByDescending {
        (if (it.clickable || it.editable) 2 else 0) + (if (it.label().isNotBlank()) 1 else 0)
    }.take(limit)
    if (ranked.isEmpty()) {
        append("(没有可识别节点。自绘界面可能需要换页或改用 tap。)")
        return@buildString
    }
    ranked.forEach { node ->
        val mark = if (itClickable(node)) "*" else "-"
        append(mark).append(' ').append(node.id).append(' ')
        append(node.label().ifBlank { node.cls }).append(" [").append(node.kind()).append(']')
        append(" (").append(node.cx).append(',').append(node.cy).append(')')
        append('\n')
    }
}

private fun itClickable(node: UiNode) = node.clickable || node.editable || node.checkable

fun findInSnapshot(snapshot: UiSnapshot, query: String): List<UiNode> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return snapshot.nodes.filter { node ->
        node.id.equals(q, true) ||
            node.text.contains(q, true) ||
            node.desc.contains(q, true) ||
            node.viewId.contains(q, true)
    }
}

fun rematchNode(old: UiNode, fresh: List<UiNode>): UiNode? {
    fresh.firstOrNull {
        it.text == old.text && it.desc == old.desc && it.viewId == old.viewId && it.label().isNotBlank()
    }?.let { return it }
    fresh.firstOrNull { it.viewId.isNotBlank() && it.viewId == old.viewId }?.let { return it }
    val labeled = fresh.filter { it.label() == old.label() && old.label().isNotBlank() }
    return labeled.minByOrNull { dist(it, old) }
}

private fun dist(a: UiNode, b: UiNode): Int {
    val dx = a.cx - b.cx
    val dy = a.cy - b.cy
    return dx * dx + dy * dy
}

fun parseDumpBounds(line: String): Pair<Int, Int>? {
    val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(line) ?: return null
    val l = m.groupValues[1].toInt()
    val t = m.groupValues[2].toInt()
    val r = m.groupValues[3].toInt()
    val b = m.groupValues[4].toInt()
    return (l + r) / 2 to (t + b) / 2
}
