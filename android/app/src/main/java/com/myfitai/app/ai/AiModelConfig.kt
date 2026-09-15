package com.myfitai.app.ai

object AiModelConfig {
    const val GEMINI_36_FLASH = "gemini-3.6-flash"
    const val GEMINI_PRIMARY = "gemini-3.5-flash"
    const val GEMINI_35_FLASH_LITE = "gemini-3.5-flash-lite"
    const val GEMINI_FALLBACK = "gemini-3.1-flash-lite"
    const val GEMINI_25_FLASH_LITE = "gemini-2.5-flash-lite"

    const val OPENAI = "gpt-4o-mini"
    const val OPENAI_GPT_5_MINI = "gpt-5-mini"
    const val OPENAI_GPT_56_LUNA = "gpt-5.6-luna"
    const val OPENAI_GPT_56_TERRA = "gpt-5.6-terra"
    const val OPENAI_GPT_56_SOL = "gpt-5.6-sol"

    val GEMINI_SELECTABLE = listOf(
        GEMINI_36_FLASH,
        GEMINI_PRIMARY,
        GEMINI_35_FLASH_LITE,
        GEMINI_FALLBACK,
        GEMINI_25_FLASH_LITE,
    )

    val OPENAI_SELECTABLE = listOf(
        OPENAI_GPT_56_SOL,
        OPENAI_GPT_56_TERRA,
        OPENAI_GPT_56_LUNA,
        OPENAI_GPT_5_MINI,
        OPENAI,
    )

    fun displayName(model: String): String = when (model) {
        GEMINI_36_FLASH -> "Gemini 3.6 Flash"
        GEMINI_PRIMARY -> "Gemini 3.5 Flash"
        GEMINI_35_FLASH_LITE -> "Gemini 3.5 Flash-Lite"
        GEMINI_FALLBACK -> "Gemini 3.1 Flash Lite"
        GEMINI_25_FLASH_LITE -> "Gemini 2.5 Flash-Lite"
        OPENAI_GPT_56_SOL -> "GPT-5.6 Sol"
        OPENAI_GPT_56_TERRA -> "GPT-5.6 Terra"
        OPENAI_GPT_56_LUNA -> "GPT-5.6 Luna"
        OPENAI_GPT_5_MINI -> "GPT-5 Mini"
        OPENAI -> "GPT-4o mini"
        else -> model
    }
}
