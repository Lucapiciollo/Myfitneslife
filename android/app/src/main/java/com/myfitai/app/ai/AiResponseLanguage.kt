package com.myfitai.app.ai

/**
 * Language contract for user-facing AI fields, shared by OpenAI and Gemini at the
 * runtime boundary. Structural identifiers remain unchanged for pipe/schema parsers.
 */
internal object AiResponseLanguage {
    fun scope(
        globalScope: String,
        compactRule: String,
        agentPrompt: String,
        compact: Boolean,
    ): String = buildString {
        append(globalScope)
        append('\n').append(ITALIAN_OUTPUT_RULE)
        if (compact) append('\n').append(compactRule)
        append("\n\n").append(agentPrompt.trim())
    }

    const val ITALIAN_OUTPUT_RULE =
        "OUTPUT_LANGUAGE: it-IT. Every user-visible natural-language field MUST contain only " +
            "Italian words and Italian grammar: meal titles, recipes, preparation, ingredients, " +
            "food names when an ordinary Italian name exists, advice, explanations, reasons, " +
            "summaries, observations, follow-up guidance, validation notes and error-related " +
            "text. Do not use English words, English headings, English status labels or mixed " +
            "Italian-English phrases in natural-language fields. Translate technical concepts " +
            "into ordinary Italian. English is allowed only in structural tokens that the app " +
            "requires exactly: schema property names, pipe record letters, protocol headers, " +
            "enum values, IDs, timestamps, units, numeric formats, provider names, model names, " +
            "brands and user quotations. Never translate or silently alter structural tokens, " +
            "proper names, brands or user quotations. No extra text outside the requested " +
            "JSON/pipe format."
}
