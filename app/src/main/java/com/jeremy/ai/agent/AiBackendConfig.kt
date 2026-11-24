package com.jeremy.ai.agent

import android.content.Context

enum class AiBackendMode(val label: String) {
    LITERT_LM("Local LiteRT-LM"),
    OLLAMA("Ollama Server")
}

data class AiBackendSettings(
    val mode: AiBackendMode = AiBackendMode.LITERT_LM,
    val ollamaBaseUrl: String = "http://127.0.0.1:11434",
    val ollamaCoderModel: String = "qwen2.5-coder:3b",
    val ollamaVisionModel: String? = "minicpm-v:latest",
    val showOnStartup: Boolean = true
)

object AiBackendConfig {
    private const val PREFS_NAME = "ai_backend_prefs"
    private const val KEY_MODE = "backend_mode"
    private const val KEY_OLLAMA_URL = "ollama_base_url"
    private const val KEY_OLLAMA_CODER_MODEL = "ollama_coder_model"
    private const val KEY_OLLAMA_VISION_MODEL = "ollama_vision_model"
    private const val KEY_SHOW_ON_STARTUP = "show_on_startup"

    private const val DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"
    private const val DEFAULT_CODER_MODEL = "qwen2.5-coder:3b"
    private const val DEFAULT_VISION_MODEL = "minicpm-v:latest"

    @Volatile
    var currentSettings: AiBackendSettings = AiBackendSettings()
        private set

    fun load(context: Context): AiBackendSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeName = prefs.getString(KEY_MODE, AiBackendMode.LITERT_LM.name) ?: AiBackendMode.LITERT_LM.name
        val mode = runCatching { AiBackendMode.valueOf(modeName) }.getOrDefault(AiBackendMode.LITERT_LM)

        val rawUrl = prefs.getString(KEY_OLLAMA_URL, DEFAULT_OLLAMA_URL)?.trim()?.removeSuffix("/")
        val rawCoder = prefs.getString(KEY_OLLAMA_CODER_MODEL, DEFAULT_CODER_MODEL)?.trim()
        val rawVision = prefs.getString(KEY_OLLAMA_VISION_MODEL, DEFAULT_VISION_MODEL)?.trim()

        val settings = AiBackendSettings(
            mode = mode,
            ollamaBaseUrl = if (rawUrl.isNullOrBlank()) DEFAULT_OLLAMA_URL else rawUrl,
            ollamaCoderModel = if (rawCoder.isNullOrBlank()) DEFAULT_CODER_MODEL else rawCoder,
            ollamaVisionModel = if (rawVision.isNullOrBlank()) null else rawVision,
            showOnStartup = prefs.getBoolean(KEY_SHOW_ON_STARTUP, true)
        )
        currentSettings = settings
        return settings
    }

    fun save(context: Context, settings: AiBackendSettings): AiBackendSettings {
        val normalizedUrl = settings.ollamaBaseUrl.trim().removeSuffix("/")
            .ifBlank { DEFAULT_OLLAMA_URL }
        val normalizedCoder = settings.ollamaCoderModel.trim()
            .ifBlank { DEFAULT_CODER_MODEL }
        val normalizedVision = settings.ollamaVisionModel?.trim()
            ?.takeIf { it.isNotEmpty() }

        val normalized = settings.copy(
            ollamaBaseUrl = normalizedUrl,
            ollamaCoderModel = normalizedCoder,
            ollamaVisionModel = normalizedVision
        )

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, normalized.mode.name)
            .putString(KEY_OLLAMA_URL, normalized.ollamaBaseUrl)
            .putString(KEY_OLLAMA_CODER_MODEL, normalized.ollamaCoderModel)
            .putString(KEY_OLLAMA_VISION_MODEL, normalized.ollamaVisionModel)
            .putBoolean(KEY_SHOW_ON_STARTUP, normalized.showOnStartup)
            .apply()

        currentSettings = normalized
        return normalized
    }
}
