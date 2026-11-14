package com.myfitai.app.ai

/** Carries only configuration/credential presence, never plaintext secrets. */
data class AiRuntimeConfig(
    val useGemini: Boolean = true,
    val geminiConfigured: Boolean = false,
    val openAiConfigured: Boolean = false,
) {
    fun selectedProvider(): AiProviderType = when {
        useGemini && geminiConfigured -> AiProviderType.GEMINI
        !useGemini && openAiConfigured -> AiProviderType.OPENAI
        else -> AiProviderType.NOT_CONFIGURED
    }
}
