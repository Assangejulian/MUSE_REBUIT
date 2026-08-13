package com.muse.app.update

fun normalizeVersion(raw: String): String =
    raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore("-")
        .substringBefore("+")

fun versionParts(raw: String): List<Int> =
    normalizeVersion(raw).split('.').map { it.toIntOrNull() ?: 0 }

fun isNewerVersion(remote: String, local: String): Boolean {
    val a = versionParts(remote)
    val b = versionParts(local)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av > bv
    }
    return false
}
