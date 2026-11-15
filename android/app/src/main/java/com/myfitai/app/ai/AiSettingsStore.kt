package com.myfitai.app.ai

import android.content.Context

/** Stores only non-secret provider preferences. Credentials remain in Keystore-backed stores. */
class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var useGemini: Boolean
        get() = prefs.getBoolean(KEY_USE_GEMINI, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_GEMINI, value).apply() }

    /**
     * Interruttore locale (MACRO L3): true = piano settimanale con protocollo MFP2 (l'IA sceglie alimenti e grammi,
     * l'app calcola macro dal catalogo). Default false = comportamento MFP1 invariato. Nessuna UI: si accende
     * modificando questa preferenza (file shared_prefs/ai_settings.xml, chiave `use_food_catalog_plan`).
     */
    var useFoodCatalogPlan: Boolean
        get() = prefs.getBoolean(KEY_USE_FOOD_CATALOG_PLAN, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_FOOD_CATALOG_PLAN, value).apply() }

    /**
     * Diagnostica MFP2 (L6d): sintesi dell'ULTIMO fallimento di una generazione dopo tutti i tentativi, nella forma
     * `<epochMillis>|attempt=N;code=CODICE;detail=...` (solo codici e numeri scritti dall'app, <= 200 caratteri). Si legge
     * da shared_prefs/ai_settings.xml, chiave `last_mfp2_failure`. Nessun testo del modello, nessun dato personale.
     */
    var lastMfp2Failure: String?
        get() = prefs.getString(KEY_LAST_MFP2_FAILURE, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_LAST_MFP2_FAILURE) else putString(KEY_LAST_MFP2_FAILURE, value.take(200))
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

    private companion object {
        const val PREFS_NAME = "ai_settings"
        const val KEY_USE_GEMINI = "use_gemini"
        const val KEY_USE_FOOD_CATALOG_PLAN = "use_food_catalog_plan"
        const val KEY_LAST_MFP2_FAILURE = "last_mfp2_failure"
        const val KEY_GEMINI_VERIFIED_MODEL = "gemini_verified_model"
        const val KEY_SELECTED_GEMINI_MODEL = "selected_gemini_model"
        const val KEY_SELECTED_OPENAI_MODEL = "selected_openai_model"
    }
}
