package com.myfitai.app.ai

import android.content.Context

/** Stores only non-secret provider preferences. Credentials remain in Keystore-backed stores. */
class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var useGemini: Boolean
        get() = prefs.getBoolean(KEY_USE_GEMINI, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_GEMINI, value).apply() }

    var geminiVerifiedModel: String?
        get() = prefs.getString(KEY_GEMINI_VERIFIED_MODEL, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_GEMINI_VERIFIED_MODEL) else putString(KEY_GEMINI_VERIFIED_MODEL, value)
            }.apply()
        }

    private companion object {
        const val PREFS_NAME = "ai_settings"
        const val KEY_USE_GEMINI = "use_gemini"
        const val KEY_GEMINI_VERIFIED_MODEL = "gemini_verified_model"
    }
}
