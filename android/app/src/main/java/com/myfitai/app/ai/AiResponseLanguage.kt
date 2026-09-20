package com.myfitai.app.ai

/**
 * Language contract for user-facing AI fields, shared by OpenAI and Gemini at the
 * runtime boundary. Structural identifiers remain unchanged for pipe/schema parsers.
 */
internal object AiResponseLanguage {
    const val ITALIAN_OUTPUT_RULE =
        "OUTPUT_LANGUAGE: it-IT. Write ALL user-visible natural-language text in Italian: " +
            "meal titles, recipes/preparation, ingredient and food names when they have an " +
            "ordinary Italian name, advice, explanations, reasons, summaries, observations, " +
            "follow-up guidance, validation notes and error-related user-facing text. " +
            "The input, system rules and protocol can be in English, but English is NOT the " +
            "output language. Preserve schema property names, pipe record letters, protocol " +
            "headers, enum values, IDs, timestamps, units and numeric formats EXACTLY as " +
            "specified. Never translate structural tokens or proper names/brands. " +
            "Do not translate or silently alter the user's own quotations. " +
            "No extra text outside the requested JSON/pipe format."
}
