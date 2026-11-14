package com.myfitai.app.ai

object AiModelConfig {
    const val GEMINI_37_FLASH = "gemini-3.7-flash"
    const val GEMINI_36_FLASH = "gemini-3.6-flash"
    const val GEMINI_35_FLASH = "gemini-3.5-flash"
    const val GEMINI_35_FLASH_LITE = "gemini-3.5-flash-lite"
    const val GEMINI_31_FLASH_LITE = "gemini-3.1-flash-lite"
    const val GEMINI_25_FLASH = "gemini-2.5-flash"
    const val GEMINI_25_FLASH_LITE = "gemini-2.5-flash-lite"

    /** Current public Gemini Lite model recommended for new users. */
    const val GEMINI_PRIMARY = GEMINI_35_FLASH_LITE
    const val GEMINI_FALLBACK = GEMINI_25_FLASH

    const val OPENAI_GPT_5 = "gpt-5"
    const val OPENAI_GPT_5_MINI = "gpt-5-mini"
    const val OPENAI_GPT_5_NANO = "gpt-5-nano"
    const val OPENAI_GPT_4O_MINI = "gpt-4o-mini"

    /** Cheapest supported OpenAI model: default for new installations. */
    const val OPENAI = OPENAI_GPT_5_NANO

    // xAI Grok (experimental, off by default). Names taken from docs.x.ai/developers/models on 13/11/2026.
    const val GROK_4_3 = "grok-4.3"
    const val GROK_4_7 = "grok-4.7"

    /** Cheapest verified Grok model with structured outputs and image input: default when Grok is enabled. */
    const val GROK = GROK_4_3

    val GROK_SELECTABLE = listOf(
        GROK_4_3,
        GROK_4_7,
    )

    // Groq (groq.com, experimental, off by default). Model IDs, strict structured outputs and modalities taken from
    // console.groq.com/docs/models, /docs/structured-outputs and /docs/vision on 14/11/2026.
    const val GROQ_GPT_OSS_20B = "openai/gpt-oss-20b"
    const val GROQ_GPT_OSS_120B = "openai/gpt-oss-120b"
    const val GROQ_QWEN_38_27B = "qwen/qwen3.8-27b"

    /** Cheapest Groq model with `strict: true` structured outputs (production, text only): default when Groq is enabled. */
    const val GROQ = GROQ_GPT_OSS_20B

    val GROQ_SELECTABLE = listOf(
        GROQ_GPT_OSS_20B,
        GROQ_GPT_OSS_120B,
        GROQ_QWEN_38_27B,
    )

    /**
     * Only the Groq models documented with image input (docs/vision: qwen/qwen3.8-27b). The gpt-oss models are text-only
     * (input: Text), so a photo request must never be sent to them: callers that offer photo features check this first.
     */
    fun groqSupportsImages(model: String): Boolean = model == GROQ_QWEN_38_27B

    val GEMINI_SELECTABLE = listOf(
        GEMINI_25_FLASH,
        GEMINI_35_FLASH_LITE,
        GEMINI_31_FLASH_LITE,
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
        GEMINI_35_FLASH_LITE -> "Gemini 3.5 Flash-Lite"
        GEMINI_31_FLASH_LITE -> "Gemini 3.1 Flash-Lite"
        GEMINI_25_FLASH -> "Gemini 2.5 Flash"
        GEMINI_25_FLASH_LITE -> "Gemini 2.5 Flash-Lite"
        OPENAI_GPT_5 -> "GPT-5"
        OPENAI_GPT_5_MINI -> "GPT-5 Mini"
        OPENAI_GPT_5_NANO -> "GPT-5 Nano"
        OPENAI_GPT_4O_MINI -> "GPT-4o mini"
        GROK_4_3 -> "Grok 4.3"
        GROK_4_7 -> "Grok 4.7"
        GROQ_GPT_OSS_20B -> "GPT-OSS 20B"
        GROQ_GPT_OSS_120B -> "GPT-OSS 120B"
        GROQ_QWEN_38_27B -> "Qwen 3.8 27B (anteprima)"
        else -> model
    }

    /** MyFitAI editorial preference, based on capability, cost and current app fit. */
    fun rating(model: String): Int = when (model) {
        GEMINI_35_FLASH_LITE, OPENAI_GPT_5_NANO -> 5
        GEMINI_35_FLASH, GEMINI_37_FLASH, OPENAI_GPT_5_MINI -> 4
        GEMINI_36_FLASH, GEMINI_31_FLASH_LITE, OPENAI_GPT_5 -> 4
        GEMINI_25_FLASH, GEMINI_25_FLASH_LITE, OPENAI_GPT_4O_MINI -> 3
        else -> 3
    }

    fun ratingLabel(model: String): String = "★".repeat(rating(model)) + "☆".repeat(5 - rating(model))
}
