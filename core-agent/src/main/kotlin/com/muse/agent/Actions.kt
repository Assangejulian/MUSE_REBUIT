package com.muse.agent

interface ActionPort {
    suspend fun openUrl(url: String): String
    suspend fun shareText(text: String): String
    suspend fun openApp(name: String): String
    suspend fun shizukuStatus(): String
    suspend fun shell(command: String): String
    suspend fun uiDump(): String
    suspend fun tap(x: Int, y: Int): String
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String
    suspend fun type(text: String): String
    suspend fun key(name: String): String
}
