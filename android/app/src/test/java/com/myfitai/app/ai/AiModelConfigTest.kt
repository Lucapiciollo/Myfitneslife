package com.myfitai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiModelConfigTest {
    @Test
    fun geminiModels_areCentralizedAndHaveStableDisplayNames() {
        assertEquals("gemini-3.5-flash", AiModelConfig.GEMINI_PRIMARY)
        assertEquals("gemini-3.1-flash-lite", AiModelConfig.GEMINI_FALLBACK)
        assertEquals("Gemini 3.5 Flash", AiModelConfig.displayName(AiModelConfig.GEMINI_PRIMARY))
        assertEquals("Gemini 3.1 Flash Lite", AiModelConfig.displayName(AiModelConfig.GEMINI_FALLBACK))
    }
}
