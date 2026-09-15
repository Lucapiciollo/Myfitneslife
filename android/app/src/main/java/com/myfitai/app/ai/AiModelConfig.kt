package com.myfitai.app.ai

object AiModelConfig {
    const val GEMINI_37_FLASH = "gemini-3.7-flash"
    const val GEMINI_36_FLASH = "gemini-3.6-flash"
    const val GEMINI_35_FLASH = "gemini-3.5-flash"
    const val GEMINI_31_FLASH_LITE = "gemini-3.1-flash-lite"
    const val GEMINI_25_FLASH = "gemini-2.5-flash"
    const val GEMINI_25_FLASH_LITE = "gemini-2.5-flash-lite"

    /** Cheapest supported Gemini model: default for new installations. */
    const val GEMINI_PRIMARY = GEMINI_25_FLASH_LITE
    const val GEMINI_FALLBACK = GEMINI_31_FLASH_LITE

    const val OPENAI_GPT_5 = "gpt-5"
    const val OPENAI_GPT_5_MINI = "gpt-5-mini"
    const val OPENAI_GPT_5_NANO = "gpt-5-nano"
    const val OPENAI_GPT_4O_MINI = "gpt-4o-mini"

    /** Cheapest supported OpenAI model: default for new installations. */
    const val OPENAI = OPENAI_GPT_5_NANO

    val GEMINI_SELECTABLE = listOf(
        GEMINI_25_FLASH_LITE,
        GEMINI_31_FLASH_LITE,
        GEMINI_25_FLASH,
        GEMINI_37_FLASH,
        GEMINI_36_FLASH,
        GEMINI_35_FLASH,
    )

    val OPENAI_SELECTABLE = listOf(
        OPENAI_GPT_5_NANO,
        OPENAI_GPT_4O_MINI,
        OPENAI_GPT_5_MINI,
        OPENAI_GPT_5,
    )

    fun displayName(model: String): String = when (model) {
        GEMINI_37_FLASH -> "Gemini 3.7 Flash"
        GEMINI_36_FLASH -> "Gemini 3.6 Flash"
        GEMINI_35_FLASH -> "Gemini 3.5 Flash"
        GEMINI_31_FLASH_LITE -> "Gemini 3.1 Flash-Lite"
        GEMINI_25_FLASH -> "Gemini 2.5 Flash"
        GEMINI_25_FLASH_LITE -> "Gemini 2.5 Flash-Lite"
        OPENAI_GPT_5 -> "GPT-5"
        OPENAI_GPT_5_MINI -> "GPT-5 Mini"
        OPENAI_GPT_5_NANO -> "GPT-5 Nano"
        OPENAI_GPT_4O_MINI -> "GPT-4o mini"
        else -> model
    }
}
