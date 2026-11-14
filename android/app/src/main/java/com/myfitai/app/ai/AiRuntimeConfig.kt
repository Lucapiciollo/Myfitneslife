package com.myfitai.app.ai

/**
 * Carries only configuration/credential presence, never plaintext secrets.
 *
 * Provider choice is three-way but backward compatible: [useGemini] stays the choice between the two historical providers
 * (and the fallback when Grok is not usable). Grok is EXPERIMENTAL and only selected when [grokEnabled] (off by default)
 * AND the user explicitly chose it ([grokSelected]). An enabled Grok without key is NOT_CONFIGURED: there is never a
 * silent switch to another provider (and its data policy).
 */
data class AiRuntimeConfig(
    val useGemini: Boolean = true,
    val geminiConfigured: Boolean = false,
    val openAiConfigured: Boolean = false,
    val grokEnabled: Boolean = false,
    val grokSelected: Boolean = false,
    val grokConfigured: Boolean = false,
) {
    /** True only when Grok is switched on and chosen: otherwise the choice behaves as the previous provider. */
    val grokActive: Boolean get() = grokEnabled && grokSelected

    fun selectedProvider(): AiProviderType = when {
        grokActive -> if (grokConfigured) AiProviderType.GROK else AiProviderType.NOT_CONFIGURED
        useGemini && geminiConfigured -> AiProviderType.GEMINI
        !useGemini && openAiConfigured -> AiProviderType.OPENAI
        else -> AiProviderType.NOT_CONFIGURED
    }
}
