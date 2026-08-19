package com.muse.memory

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.muse.llm.DEFAULT_BASE_URL
import com.muse.llm.DEFAULT_MAX_TOKENS
import com.muse.llm.MODEL_FLASH
import com.muse.llm.ModelProvider
import com.muse.llm.knownBaseUrls
import com.muse.llm.modelOption
import com.muse.llm.modelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MuseSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = MODEL_FLASH,
    val reasoningEffort: String = "high",
    val thinkingEnabled: Boolean = true,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val theme: String = "cream",
    val floatOnTask: Boolean = true,
    val taskMode: Boolean = false,
    val geminiKey: String = "",
    val qwenKey: String = "",
) {
    fun keyForModel(modelId: String = model): String = when (modelProvider(modelId)) {
        ModelProvider.Gemini -> geminiKey.ifBlank { apiKey }
        ModelProvider.Qwen -> qwenKey.ifBlank { apiKey }
        ModelProvider.DeepSeek -> apiKey
    }

    fun withModel(id: String): MuseSettings {
        val next = modelOption(id)
        val trimmed = baseUrl.trim().trimEnd('/')
        val switchUrl = trimmed.isEmpty() || trimmed in knownBaseUrls()
        return copy(model = id, baseUrl = if (switchUrl) next.defaultBase else baseUrl)
    }
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<MuseSettings> = _settings.asStateFlow()

    fun current(): MuseSettings = _settings.value

    fun update(transform: (MuseSettings) -> MuseSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_API, next.apiKey)
            .putString(KEY_BASE, next.baseUrl)
            .putString(KEY_MODEL, next.model)
            .putString(KEY_EFFORT, next.reasoningEffort)
            .putBoolean(KEY_THINKING, next.thinkingEnabled)
            .putInt(KEY_MAX, next.maxTokens)
            .putString(KEY_THEME, next.theme)
            .putBoolean(KEY_FLOAT, next.floatOnTask)
            .putBoolean(KEY_TASK_MODE, next.taskMode)
            .putString(KEY_GEMINI, next.geminiKey)
            .putString(KEY_QWEN, next.qwenKey)
            .apply()
        _settings.value = next
    }

    private fun read(): MuseSettings = MuseSettings(
        apiKey = prefs.getString(KEY_API, "").orEmpty(),
        baseUrl = prefs.getString(KEY_BASE, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
        model = prefs.getString(KEY_MODEL, MODEL_FLASH) ?: MODEL_FLASH,
        reasoningEffort = prefs.getString(KEY_EFFORT, "high") ?: "high",
        thinkingEnabled = prefs.getBoolean(KEY_THINKING, true),
        maxTokens = prefs.getInt(KEY_MAX, DEFAULT_MAX_TOKENS),
        theme = prefs.getString(KEY_THEME, "cream") ?: "cream",
        floatOnTask = prefs.getBoolean(KEY_FLOAT, true),
        taskMode = prefs.getBoolean(KEY_TASK_MODE, false),
        geminiKey = prefs.getString(KEY_GEMINI, "").orEmpty(),
        qwenKey = prefs.getString(KEY_QWEN, "").orEmpty(),
    )

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val master = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    private companion object {
        const val PREFS_NAME = "muse_secure"
        const val PREFS_FALLBACK = "muse_settings_fallback"
        const val KEY_API = "api_key"
        const val KEY_BASE = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_EFFORT = "effort"
        const val KEY_THINKING = "thinking"
        const val KEY_MAX = "max_tokens"
        const val KEY_THEME = "theme"
        const val KEY_FLOAT = "float_on_task"
        const val KEY_TASK_MODE = "task_mode"
        const val KEY_GEMINI = "gemini_key"
        const val KEY_QWEN = "qwen_key"
    }
}
