package com.myfitai.app.ai

/**
 * Provider marker only. The OpenAI API key must never be carried by this object.
 * The future transport layer will read it just-in-time from SecureOpenAiKeyStore
 * for the duration of a request and will not cache it.
 */
class OpenAiProvider : AiProvider {
    override val type: AiProviderType = AiProviderType.OPENAI
}
