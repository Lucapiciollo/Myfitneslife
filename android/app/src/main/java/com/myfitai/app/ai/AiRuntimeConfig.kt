package com.myfitai.app.ai

/**
 * Carries only configuration/credential presence, never plaintext secrets.
 *
 * Provider choice is multi-way but backward compatible: [useGemini] stays the choice between the two historical providers
 * (and the fallback when an experimental provider is not usable). Grok and Groq are EXPERIMENTAL and only selected when their
 * switch ([grokEnabled] / [groqEnabled], off by default) is on AND the user explicitly chose them ([grokSelected] /
 * [groqSelected]). An enabled experimental provider without key is NOT_CONFIGURED: there is never a silent switch to
 * another provider (and its data policy). The explicit choice is a single value, so both selections are never true at once;
 * should that happen anyway, Grok wins deterministically.
 */
data class AiRuntimeConfig(
    val useGemini: Boolean = true,
    val geminiConfigured: Boolean = false,
    val openAiConfigured: Boolean = false,
    val grokEnabled: Boolean = false,
    val grokSelected: Boolean = false,
    val grokConfigured: Boolean = false,
    val groqEnabled: Boolean = false,
    val groqSelected: Boolean = false,
    val groqConfigured: Boolean = false,
) {
    /** True only when Grok is switched on and chosen: otherwise the choice behaves as the previous provider. */
    val grokActive: Boolean get() = grokEnabled && grokSelected

    /** True only when Groq is switched on and chosen (and Grok is not): otherwise the choice behaves as the previous provider. */
    val groqActive: Boolean get() = groqEnabled && groqSelected && !grokActive

    fun selectedProvider(): AiProviderType = when {
        grokActive -> if (grokConfigured) AiProviderType.GROK else AiProviderType.NOT_CONFIGURED
        groqActive -> if (groqConfigured) AiProviderType.GROQ else AiProviderType.NOT_CONFIGURED
        useGemini && geminiConfigured -> AiProviderType.GEMINI
        !useGemini && openAiConfigured -> AiProviderType.OPENAI
        else -> AiProviderType.NOT_CONFIGURED
    }
}
