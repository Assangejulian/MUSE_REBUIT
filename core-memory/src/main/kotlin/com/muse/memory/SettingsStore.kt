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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
    val extraKeys: Map<String, String> = emptyMap(),
) {
    fun keyForProvider(provider: ModelProvider): String = when (provider) {
        ModelProvider.DeepSeek -> apiKey
        ModelProvider.Gemini -> geminiKey
        ModelProvider.Qwen -> qwenKey
        else -> extraKeys[provider.name].orEmpty()
    }

    fun keyForModel(modelId: String = model): String = keyForProvider(modelProvider(modelId))

    fun hasAnyKey(): Boolean =
        apiKey.isNotBlank() || geminiKey.isNotBlank() || qwenKey.isNotBlank() ||
            extraKeys.values.any { it.isNotBlank() }

    fun withProviderKey(provider: ModelProvider, key: String): MuseSettings {
        val trimmed = key.trim()
        return when (provider) {
            ModelProvider.DeepSeek -> copy(apiKey = trimmed)
            ModelProvider.Gemini -> copy(geminiKey = trimmed)
            ModelProvider.Qwen -> copy(qwenKey = trimmed)
            else -> copy(extraKeys = extraKeys + (provider.name to trimmed))
        }
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
            .putString(KEY_EXTRA, encodeKeys(next.extraKeys))
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
        extraKeys = decodeKeys(prefs.getString(KEY_EXTRA, "").orEmpty()),
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
        const val KEY_EXTRA = "provider_keys"
    }
}

private val KeyJson = Json { ignoreUnknownKeys = true }

private fun encodeKeys(map: Map<String, String>): String {
    val kept = map.filterValues { it.isNotBlank() }
    if (kept.isEmpty()) return ""
    return buildJsonObject {
        kept.forEach { (name, value) -> put(name, value) }
    }.toString()
}

private fun decodeKeys(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return try {
        KeyJson.parseToJsonElement(raw).let { el ->
            (el as? JsonObject)?.mapNotNull { (k, v) ->
                val value = (v as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (value.isBlank()) null else k to value
            }?.toMap().orEmpty()
        }
    } catch (_: Exception) {
        emptyMap()
    }
}
