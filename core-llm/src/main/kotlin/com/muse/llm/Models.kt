package com.muse.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val MuseJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

/** Tool schema must keep default fields such as type=function. */
val ToolJson = Json(MuseJson) {
    encodeDefaults = true
}

const val MODEL_FLASH = "deepseek-v4-flash"
const val MODEL_PRO = "deepseek-v4-pro"
const val MODEL_GEMINI_FLASH = "gemini-2.5-flash"
const val MODEL_GEMINI_PRO = "gemini-2.5-pro"
const val MODEL_GEMINI_36_FLASH = "gemini-3.6-flash"
const val MODEL_GEMINI_31_PRO = "gemini-3.1-pro-preview"
const val MODEL_QWEN_PLUS = "qwen-plus"
const val MODEL_QWEN_MAX = "qwen-max"
const val MODEL_QWEN_37_PLUS = "qwen3.7-plus"
const val MODEL_QWEN_38_MAX = "qwen3.8-max"
const val MODEL_GPT_SOL = "gpt-5.6-sol"
const val MODEL_GPT_TERRA = "gpt-5.6-terra"
const val MODEL_GPT_LUNA = "gpt-5.6-luna"
const val MODEL_GROK_46 = "grok-4.6"
const val MODEL_GROK_45 = "grok-4.5"
const val MODEL_KIMI_K3 = "kimi-k3"
const val MODEL_KIMI_K26 = "kimi-k2.6"
const val MODEL_GLM_5 = "glm-5"
const val MODEL_GLM_47 = "glm-4.7"
const val MODEL_MINIMAX_M3 = "MiniMax-M3"
const val MODEL_MINIMAX_M27 = "MiniMax-M2.7"
const val MODEL_OR_SONNET = "anthropic/claude-sonnet-4.6"
const val MODEL_OR_TERRA = "openai/gpt-5.6-terra"
const val DEFAULT_BASE_URL = "https://api.deepseek.com"
const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
const val QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
const val OPENAI_BASE_URL = "https://api.openai.com/v1"
const val XAI_BASE_URL = "https://api.x.ai/v1"
const val MOONSHOT_BASE_URL = "https://api.moonshot.cn/v1"
const val ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
const val MINIMAX_BASE_URL = "https://api.minimax.io/v1"
const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
const val DEFAULT_MAX_TOKENS = 4096
const val MAX_TOKENS_CAP = 32_768

enum class ModelProvider(val label: String) {
    DeepSeek("DeepSeek"),
    Qwen("Qwen"),
    Gemini("Gemini"),
    OpenAI("OpenAI"),
    Xai("xAI"),
    Moonshot("Kimi"),
    Zhipu("GLM"),
    MiniMax("MiniMax"),
    OpenRouter("OpenRouter"),
}

data class ModelOption(
    val id: String,
    val label: String,
    val provider: ModelProvider,
    val defaultBase: String,
    val thinking: Boolean,
)

val MODEL_CATALOG: List<ModelOption> = listOf(
    ModelOption(MODEL_FLASH, "Flash", ModelProvider.DeepSeek, DEFAULT_BASE_URL, true),
    ModelOption(MODEL_PRO, "Pro", ModelProvider.DeepSeek, DEFAULT_BASE_URL, true),
    ModelOption(MODEL_QWEN_PLUS, "Plus", ModelProvider.Qwen, QWEN_BASE_URL, false),
    ModelOption(MODEL_QWEN_MAX, "Max", ModelProvider.Qwen, QWEN_BASE_URL, false),
    ModelOption(MODEL_QWEN_37_PLUS, "3.7 Plus", ModelProvider.Qwen, QWEN_BASE_URL, false),
    ModelOption(MODEL_QWEN_38_MAX, "3.8 Max", ModelProvider.Qwen, QWEN_BASE_URL, false),
    ModelOption(MODEL_GEMINI_FLASH, "2.5 Flash", ModelProvider.Gemini, GEMINI_BASE_URL, false),
    ModelOption(MODEL_GEMINI_PRO, "2.5 Pro", ModelProvider.Gemini, GEMINI_BASE_URL, false),
    ModelOption(MODEL_GEMINI_36_FLASH, "3.6 Flash", ModelProvider.Gemini, GEMINI_BASE_URL, false),
    ModelOption(MODEL_GEMINI_31_PRO, "3.1 Pro", ModelProvider.Gemini, GEMINI_BASE_URL, false),
    ModelOption(MODEL_GPT_SOL, "Sol", ModelProvider.OpenAI, OPENAI_BASE_URL, false),
    ModelOption(MODEL_GPT_TERRA, "Terra", ModelProvider.OpenAI, OPENAI_BASE_URL, false),
    ModelOption(MODEL_GPT_LUNA, "Luna", ModelProvider.OpenAI, OPENAI_BASE_URL, false),
    ModelOption(MODEL_GROK_46, "4.6", ModelProvider.Xai, XAI_BASE_URL, false),
    ModelOption(MODEL_GROK_45, "4.5", ModelProvider.Xai, XAI_BASE_URL, false),
    ModelOption(MODEL_KIMI_K3, "K3", ModelProvider.Moonshot, MOONSHOT_BASE_URL, false),
    ModelOption(MODEL_KIMI_K26, "K2.6", ModelProvider.Moonshot, MOONSHOT_BASE_URL, false),
    ModelOption(MODEL_GLM_5, "5", ModelProvider.Zhipu, ZHIPU_BASE_URL, false),
    ModelOption(MODEL_GLM_47, "4.7", ModelProvider.Zhipu, ZHIPU_BASE_URL, false),
    ModelOption(MODEL_MINIMAX_M3, "M3", ModelProvider.MiniMax, MINIMAX_BASE_URL, false),
    ModelOption(MODEL_MINIMAX_M27, "M2.7", ModelProvider.MiniMax, MINIMAX_BASE_URL, false),
    ModelOption(MODEL_OR_SONNET, "Sonnet 4.6", ModelProvider.OpenRouter, OPENROUTER_BASE_URL, false),
    ModelOption(MODEL_OR_TERRA, "GPT Terra", ModelProvider.OpenRouter, OPENROUTER_BASE_URL, false),
)

fun modelOption(id: String): ModelOption =
    MODEL_CATALOG.firstOrNull { it.id == id } ?: MODEL_CATALOG.first()

fun modelProvider(id: String): ModelProvider = modelOption(id).provider

fun modelShortLabel(id: String): String {
    val opt = modelOption(id)
    return if (opt.provider == ModelProvider.DeepSeek) opt.label else opt.provider.label
}

fun knownBaseUrls(): Set<String> = MODEL_CATALOG.map { it.defaultBase.trimEnd('/') }.toSet() + setOf(
    "https://api.moonshot.ai/v1",
    "https://api.z.ai/api/paas/v4",
    "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    "https://api.minimaxi.com/v1",
)

fun usesDeepSeekThinking(model: String): Boolean = modelOption(model).thinking

@Serializable
data class ToolFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunctionCall,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
) {
    fun toApiJson(includeReasoning: Boolean = true): JsonObject = buildJsonObject {
        put("role", role)
        when {
            role == "tool" -> put("content", content ?: "")
            toolCalls != null -> {
                put("content", content ?: "")
                if (includeReasoning) {
                    // DeepSeek returns 400 if reasoning_content is dropped after a tool call.
                    put("reasoning_content", reasoningContent ?: "")
                }
                put("tool_calls", ToolJson.encodeToJsonElement(ToolCall.serializer().let {
                    kotlinx.serialization.builtins.ListSerializer(it)
                }, toolCalls))
            }
            else -> {
                if (content != null) put("content", content)
                if (includeReasoning && !reasoningContent.isNullOrEmpty()) {
                    put("reasoning_content", reasoningContent)
                }
            }
        }
        if (toolCallId != null) put("tool_call_id", toolCallId)
        if (name != null) put("name", name)
    }
}

@Serializable
data class ToolParameterSchema(
    val type: String = "object",
    val properties: JsonObject = buildJsonObject {},
    val required: List<String> = emptyList(),
    val additionalProperties: Boolean = false,
)

@Serializable
data class ToolFunctionDef(
    val name: String,
    val description: String,
    val parameters: ToolParameterSchema,
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunctionDef,
)

@Serializable
data class ThinkingConfig(
    val type: String,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val stream: Boolean = true,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val reasoningEffort: String = "high",
    val thinkingEnabled: Boolean = true,
) {
    fun toBody(): String {
        val body = buildJsonObject {
            put("model", model)
            put("stream", stream)
            put("max_tokens", maxTokens.coerceIn(256, MAX_TOKENS_CAP))
            val deepseek = usesDeepSeekThinking(model)
            put("messages", JsonArray(messages.map { it.toApiJson(includeReasoning = deepseek) }))
            if (tools.isNotEmpty()) {
                put("tools", ToolJson.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(ToolDefinition.serializer()),
                    tools,
                ))
            }
            if (deepseek) {
                if (thinkingEnabled) {
                    put("reasoning_effort", reasoningEffort)
                    put("thinking", buildJsonObject { put("type", "enabled") })
                } else {
                    put("thinking", buildJsonObject { put("type", "disabled") })
                }
            }
        }
        return MuseJson.encodeToString(JsonObject.serializer(), body)
    }
}

fun normalizeChatEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/chat/completions") -> trimmed
        else -> "$trimmed/chat/completions"
    }
}

fun isAllowedEndpoint(baseUrl: String, allowLoopback: Boolean): Boolean {
    val trimmed = baseUrl.trim()
    if (trimmed.startsWith("https://", ignoreCase = true)) return true
    if (!allowLoopback) return false
    return trimmed.startsWith("http://127.0.0.1", ignoreCase = true) ||
        trimmed.startsWith("http://localhost", ignoreCase = true)
}

fun toolSchema(
    name: String,
    description: String,
    properties: JsonObject = buildJsonObject {},
    required: List<String> = emptyList(),
): ToolDefinition = ToolDefinition(
    function = ToolFunctionDef(
        name = name,
        description = description,
        parameters = ToolParameterSchema(
            properties = properties,
            required = required,
            additionalProperties = false,
        ),
    ),
)

fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun buildProps(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    for ((key, desc) in pairs) {
        put(key, stringProp(desc))
    }
}

fun buildEnumProp(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}
