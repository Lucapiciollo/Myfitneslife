package com.myfitai.app.ai

/** Provider marker only. The future transport must load the API key just-in-time from SecureOpenAiKeyStore. */
class OpenAiProvider : AiProvider {
    override val type: AiProviderType = AiProviderType.OPENAI
}
