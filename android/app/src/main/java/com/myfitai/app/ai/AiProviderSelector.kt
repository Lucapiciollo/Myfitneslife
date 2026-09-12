package com.myfitai.app.ai

object AiProviderSelector {
    fun create(config: AiRuntimeConfig): AiProvider? = when (config.selectedProvider()) {
        AiProviderType.GEMINI -> GeminiProvider()
        AiProviderType.OPENAI -> OpenAiProvider()
        AiProviderType.NOT_CONFIGURED -> null
    }
}
