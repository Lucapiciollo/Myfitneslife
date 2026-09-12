package com.myfitai.app.ai

/**
 * Runtime configuration deliberately carries only credential presence, never
 * the persisted OpenAI API key itself. The key is loaded just-in-time from
 * SecureOpenAiKeyStore by the OpenAI transport layer and must not be cached.
 */
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
