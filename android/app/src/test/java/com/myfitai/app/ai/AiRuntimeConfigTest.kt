package com.myfitai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiRuntimeConfigTest {
    @Test
    fun selectedProvider_requiresConfiguredCredentialForSelectedProvider() {
        assertEquals(
            AiProviderType.NOT_CONFIGURED,
            AiRuntimeConfig(useGemini = true, geminiConfigured = false).selectedProvider(),
        )
        assertEquals(
            AiProviderType.OPENAI,
            AiRuntimeConfig(useGemini = false, openAiConfigured = true).selectedProvider(),
        )
        assertEquals(
            AiProviderType.GEMINI,
            AiRuntimeConfig(useGemini = true, geminiConfigured = true).selectedProvider(),
        )
    }
}
