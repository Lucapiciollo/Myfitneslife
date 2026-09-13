package com.myfitai.app.ai

import android.content.Context

/** Stores only non-secret provider preferences. Credentials remain in Keystore-backed stores. */
class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var useGemini: Boolean
        get() = prefs.getBoolean(KEY_USE_GEMINI, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_GEMINI, value).apply() }

    private companion object {
        const val PREFS_NAME = "ai_settings"
        const val KEY_USE_GEMINI = "use_gemini"
    }
}
