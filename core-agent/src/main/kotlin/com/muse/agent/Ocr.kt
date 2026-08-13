package com.muse.agent

data class OcrHit(
    val text: String,
    val cx: Int,
    val cy: Int,
)

fun formatOcrHits(hits: List<OcrHit>, source: String, limit: Int = 40): String {
    val kept = hits
        .map { it.copy(text = it.text.replace('\n', ' ').trim()) }
        .filter { it.text.isNotEmpty() }
        .distinctBy { "${it.text}|${it.cx / 8}|${it.cy / 8}" }
        .take(limit)
    if (kept.isEmpty()) {
        return "source=$source\n(没有识别到文字。可再 ui_snapshot，或换个角度/等界面稳定后再 ocr_screen。)"
    }
    return buildString {
        append("source=").append(source)
        append(" hits=").append(kept.size)
        append('\n')
        kept.forEach { hit ->
            append("- ").append(hit.text.take(40))
            append(" (").append(hit.cx).append(',').append(hit.cy).append(')')
            append('\n')
        }
    }.trimEnd()
}
