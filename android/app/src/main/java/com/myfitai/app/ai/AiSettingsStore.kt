package com.myfitai.app.ai

import android.content.Context

/** Stores only non-secret provider preferences. Credentials remain in Keystore-backed stores. */
class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Legacy two-way choice, kept as the source of truth for installations that never saw Grok and as the "previous provider"
     * fallback. A legacy write also clears an explicit [selectedProvider] so older UI code keeps working unchanged.
     */
    var useGemini: Boolean
        get() = prefs.getBoolean(KEY_USE_GEMINI, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_GEMINI, value).remove(KEY_SELECTED_PROVIDER).apply() }

    /**
     * Explicit provider choice (GEMINI, OPENAI or experimental GROK). Read-time migration: when never written, it is derived
     * from [useGemini] (GEMINI if true, OPENAI otherwise), so existing installations keep exactly what they use today.
     * Choosing GROK does not touch [useGemini], which remains the fallback if Grok is disabled.
     */
    var selectedProvider: AiProviderType
        get() = resolveSelectedProvider(prefs.getString(KEY_SELECTED_PROVIDER, null), useGemini)
        set(value) {
            require(value != AiProviderType.NOT_CONFIGURED) { "NOT_CONFIGURED is not a selectable provider" }
            prefs.edit().apply {
                putString(KEY_SELECTED_PROVIDER, value.name)
                when (value) {
                    AiProviderType.GEMINI -> putBoolean(KEY_USE_GEMINI, true)
                    AiProviderType.OPENAI -> putBoolean(KEY_USE_GEMINI, false)
                    else -> Unit
                }
            }.apply()
        }

    var geminiVerifiedModel: String?
        get() = prefs.getString(KEY_GEMINI_VERIFIED_MODEL, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_GEMINI_VERIFIED_MODEL) else putString(KEY_GEMINI_VERIFIED_MODEL, value)
            }.apply()
        }

    var selectedGeminiModel: String
        get() = prefs.getString(KEY_SELECTED_GEMINI_MODEL, AiModelConfig.GEMINI_PRIMARY)
            ?.takeIf { it in AiModelConfig.GEMINI_SELECTABLE }
            ?: AiModelConfig.GEMINI_PRIMARY
        set(value) {
            require(value in AiModelConfig.GEMINI_SELECTABLE)
            prefs.edit().putString(KEY_SELECTED_GEMINI_MODEL, value).apply()
        }

    var selectedOpenAiModel: String
        get() = prefs.getString(KEY_SELECTED_OPENAI_MODEL, AiModelConfig.OPENAI)
            ?.takeIf { it in AiModelConfig.OPENAI_SELECTABLE }
            ?: AiModelConfig.OPENAI
        set(value) {
            require(value in AiModelConfig.OPENAI_SELECTABLE)
            prefs.edit().putString(KEY_SELECTED_OPENAI_MODEL, value).apply()
        }

    /** Experimental xAI Grok switch: OFF by default. Grok is only selected when this is true AND [selectedProvider] is GROK. */
    var grokEnabled: Boolean
        get() = prefs.getBoolean(KEY_GROK_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_GROK_ENABLED, value).apply() }

    var selectedGrokModel: String
        get() = prefs.getString(KEY_SELECTED_GROK_MODEL, AiModelConfig.GROK)
            ?.takeIf { it in AiModelConfig.GROK_SELECTABLE }
            ?: AiModelConfig.GROK
        set(value) {
            require(value in AiModelConfig.GROK_SELECTABLE)
            prefs.edit().putString(KEY_SELECTED_GROK_MODEL, value).apply()
        }

    companion object {
        /** Pure migration rule (unit-testable): absent/unknown value falls back to the legacy boolean. NOT_CONFIGURED is never persisted. */
        internal fun resolveSelectedProvider(stored: String?, useGemini: Boolean): AiProviderType {
            val parsed = AiProviderType.entries.firstOrNull { it.name == stored && it != AiProviderType.NOT_CONFIGURED }
            return parsed ?: if (useGemini) AiProviderType.GEMINI else AiProviderType.OPENAI
        }

        private const val PREFS_NAME = "ai_settings"
        private const val KEY_USE_GEMINI = "use_gemini"
        private const val KEY_SELECTED_PROVIDER = "selected_provider"
        private const val KEY_GEMINI_VERIFIED_MODEL = "gemini_verified_model"
        private const val KEY_SELECTED_GEMINI_MODEL = "selected_gemini_model"
        private const val KEY_SELECTED_OPENAI_MODEL = "selected_openai_model"
        private const val KEY_GROK_ENABLED = "grok_enabled"
        private const val KEY_SELECTED_GROK_MODEL = "selected_grok_model"
    }
}

