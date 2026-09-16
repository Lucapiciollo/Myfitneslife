package com.myfitai.app.ai

import android.content.Context
import com.myfitai.app.security.SecureAiCredentialStore

object AiProviderSelector {
    fun create(context: Context, config: AiRuntimeConfig): AiProvider? {
        val settings = AiSettingsStore(context)
        val credentials = SecureAiCredentialStore(context)
        return when (config.selectedProvider()) {
            AiProviderType.GEMINI -> GeminiByokProvider(
                credentialStore = credentials,
                primaryModel = settings.selectedGeminiModel,
                // A persisted model can become unavailable as the provider catalog evolves.
                // Keep a known fallback independent from the user's selected primary model so a
                // 404 MODEL_UNAVAILABLE can recover instead of retrying the same URL.
                fallbackModel = if (settings.selectedGeminiModel == AiModelConfig.GEMINI_25_FLASH) {
                    AiModelConfig.GEMINI_35_FLASH_LITE
                } else {
                    AiModelConfig.GEMINI_25_FLASH
                },
            )
            AiProviderType.OPENAI -> OpenAiProvider(
                credentialStore = credentials,
                model = settings.selectedOpenAiModel,
            )
            AiProviderType.NOT_CONFIGURED -> null
        }
    }
}
