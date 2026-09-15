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
                fallbackModel = settings.selectedGeminiModel,
            )
            AiProviderType.OPENAI -> OpenAiProvider(
                credentialStore = credentials,
                model = settings.selectedOpenAiModel,
            )
            AiProviderType.NOT_CONFIGURED -> null
        }
    }
}
