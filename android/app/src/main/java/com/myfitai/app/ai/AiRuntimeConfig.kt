package com.myfitai.app.ai

/** Runtime configuration contains only credential presence, never the raw key. */
data class AiRuntimeConfig(
    val useGemini: Boolean = true,
    val openAiConfigured: Boolean = false
) {
    fun selectedProvider(): AiProviderType = when {
        useGemini -> AiProviderType.GEMINI
        openAiConfigured -> AiProviderType.OPENAI
        else -> AiProviderType.NOT_CONFIGURED
    }
}
