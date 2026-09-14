package com.myfitai.app.ai

object AiModelConfig {
    const val GEMINI_PRIMARY = "gemini-3.5-flash"
    const val GEMINI_FALLBACK = "gemini-3.1-flash-lite"
    const val OPENAI = "gpt-4o-mini"

    fun displayName(model: String): String = when (model) {
        GEMINI_PRIMARY -> "Gemini 3.5 Flash"
        GEMINI_FALLBACK -> "Gemini 3.1 Flash Lite"
        OPENAI -> "GPT-4o mini"
        else -> model
    }
}
