package com.muse.agent

interface ActionPort {
    suspend fun openUrl(url: String): String
    suspend fun shareText(text: String): String
    suspend fun openApp(name: String): String
}
