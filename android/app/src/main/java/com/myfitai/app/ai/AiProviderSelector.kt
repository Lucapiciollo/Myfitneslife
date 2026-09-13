package com.myfitai.app.ai

import android.content.Context
import com.myfitai.app.security.SecureGeminiKeyStore
import com.myfitai.app.security.SecureOpenAiKeyStore

object AiProviderSelector {
    fun create(context: Context, config: AiRuntimeConfig): AiProvider? = when (config.selectedProvider()) {
        AiProviderType.GEMINI -> GeminiProvider(SecureGeminiKeyStore(context))
        AiProviderType.OPENAI -> OpenAiProvider(SecureOpenAiKeyStore(context))
        AiProviderType.NOT_CONFIGURED -> null
    }
}
