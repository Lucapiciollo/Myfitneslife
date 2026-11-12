package com.myfitai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderStatusTest {
    @Test
    fun selectedProviderWithKey_isActiveAndNeedsNoAttention() {
        val gemini = AiProviderStatus.from(AiRuntimeConfig(useGemini = true, geminiConfigured = true))
        assertEquals(AiProviderStatus.State.ACTIVE, gemini.state)
        assertEquals(AiProviderType.GEMINI, gemini.selected)
        assertNull(gemini.alternative)
        assertFalse(gemini.needsAttention)

        val openAi = AiProviderStatus.from(AiRuntimeConfig(useGemini = false, openAiConfigured = true))
        assertEquals(AiProviderStatus.State.ACTIVE, openAi.state)
        assertEquals(AiProviderType.OPENAI, openAi.selected)
        assertFalse(openAi.needsAttention)
    }

    @Test
    fun activeStateIgnoresTheOtherProviderKey() {
        val status = AiProviderStatus.from(AiRuntimeConfig(useGemini = true, geminiConfigured = true, openAiConfigured = true))
        assertEquals(AiProviderStatus.State.ACTIVE, status.state)
        assertNull(status.alternative)
    }

    @Test
    fun noKeyAtAll_needsAttentionWithoutAlternative() {
        val status = AiProviderStatus.from(AiRuntimeConfig(useGemini = true))
        assertEquals(AiProviderStatus.State.NONE, status.state)
        assertEquals(AiProviderType.GEMINI, status.selected)
        assertNull(status.alternative)
        assertTrue(status.needsAttention)
    }

    @Test
    fun selectedWithoutKeyButOtherConfigured_offersExplicitAlternativeAndNeedsAttention() {
        val geminiSelected = AiProviderStatus.from(AiRuntimeConfig(useGemini = true, openAiConfigured = true))
        assertEquals(AiProviderStatus.State.SELECTED_WITHOUT_KEY, geminiSelected.state)
        assertEquals(AiProviderType.GEMINI, geminiSelected.selected)
        assertEquals(AiProviderType.OPENAI, geminiSelected.alternative)
        assertTrue(geminiSelected.needsAttention)

        val openAiSelected = AiProviderStatus.from(AiRuntimeConfig(useGemini = false, geminiConfigured = true))
        assertEquals(AiProviderStatus.State.SELECTED_WITHOUT_KEY, openAiSelected.state)
        assertEquals(AiProviderType.OPENAI, openAiSelected.selected)
        assertEquals(AiProviderType.GEMINI, openAiSelected.alternative)
        assertTrue(openAiSelected.needsAttention)
    }

    @Test
    fun needsAttention_matchesRuntimeSelectionForEveryCombination() {
        for (useGemini in listOf(true, false)) for (gemini in listOf(true, false)) for (openAi in listOf(true, false)) {
            val config = AiRuntimeConfig(useGemini, gemini, openAi)
            val usable = config.selectedProvider() != AiProviderType.NOT_CONFIGURED
            assertEquals("useGemini=$useGemini gemini=$gemini openAi=$openAi", !usable, AiProviderStatus.from(config).needsAttention)
        }
    }
}
