package com.myfitai.app.ai

import android.content.Context
import com.myfitai.app.security.SecureAiCredentialStore

object AiProviderSelector {
    fun create(context: Context, config: AiRuntimeConfig): AiProvider? = when (config.selectedProvider()) {
        AiProviderType.GEMINI -> GeminiByokProvider(SecureAiCredentialStore(context))
        AiProviderType.OPENAI -> OpenAiProvider(SecureAiCredentialStore(context))
        AiProviderType.NOT_CONFIGURED -> null
    }
}
