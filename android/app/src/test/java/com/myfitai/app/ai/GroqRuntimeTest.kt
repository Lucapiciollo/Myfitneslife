package com.myfitai.app.ai

import com.myfitai.app.security.AiCredentialProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Offline tests of the Groq runtime wiring: selection, legacy compatibility, credentials slot, pricing and cost. */
class GroqRuntimeTest {
    // ---- selection ----

    @Test
    fun defaults_keepGroqOffAndLegacyBehaviourUnchanged() {
        val fresh = AiRuntimeConfig()
        assertFalse(fresh.groqEnabled)
        assertFalse(fresh.groqActive)
        assertEquals(AiProviderType.NOT_CONFIGURED, fresh.selectedProvider())
        // Old installations: only useGemini plus keys; a Groq key alone never changes the choice.
        assertEquals(AiProviderType.GEMINI, AiRuntimeConfig(useGemini = true, geminiConfigured = true, openAiConfigured = true, groqConfigured = true).selectedProvider())
        assertEquals(AiProviderType.OPENAI, AiRuntimeConfig(useGemini = false, geminiConfigured = true, openAiConfigured = true, groqConfigured = true).selectedProvider())
        assertEquals(AiProviderType.NOT_CONFIGURED, AiRuntimeConfig(groqConfigured = true).selectedProvider())
    }

    @Test
    fun groqChosenButDisabled_fallsBackToThePreviousProvider() {
        val gemini = AiRuntimeConfig(useGemini = true, geminiConfigured = true, groqEnabled = false, groqSelected = true, groqConfigured = true)
        assertEquals(AiProviderType.GEMINI, gemini.selectedProvider())
        val openAi = AiRuntimeConfig(useGemini = false, openAiConfigured = true, groqEnabled = false, groqSelected = true, groqConfigured = true)
        assertEquals(AiProviderType.OPENAI, openAi.selectedProvider())
    }

    @Test
    fun groqIsSelectedOnlyWhenEnabledChosenAndConfigured() {
        val active = AiRuntimeConfig(useGemini = true, geminiConfigured = true, groqEnabled = true, groqSelected = true, groqConfigured = true)
        assertEquals(AiProviderType.GROQ, active.selectedProvider())
        // Enabled but not chosen: the historical provider keeps working.
        assertEquals(AiProviderType.GEMINI, active.copy(groqSelected = false).selectedProvider())
        // Chosen and enabled but no key: never a silent switch to another provider.
        assertEquals(AiProviderType.NOT_CONFIGURED, active.copy(groqConfigured = false).selectedProvider())
    }

    @Test
    fun grokAndGroqSelectionsAreIndependent() {
        val grok = AiRuntimeConfig(useGemini = true, geminiConfigured = true, grokEnabled = true, grokSelected = true, grokConfigured = true, groqEnabled = true, groqConfigured = true)
        assertEquals(AiProviderType.GROK, grok.selectedProvider())
        // a stale Grok choice with Grok switched off must not shadow an active Groq choice
        val groq = AiRuntimeConfig(grokEnabled = false, grokSelected = true, grokConfigured = true, groqEnabled = true, groqSelected = true, groqConfigured = true)
        assertEquals(AiProviderType.GROQ, groq.selectedProvider())
    }

    @Test
    fun resolveSelectedProvider_acceptsGroqAndStillMigratesLegacyBoolean() {
        assertEquals(AiProviderType.GROQ, AiSettingsStore.resolveSelectedProvider("GROQ", useGemini = true))
        assertEquals(AiProviderType.GEMINI, AiSettingsStore.resolveSelectedProvider(null, useGemini = true))
        assertEquals(AiProviderType.OPENAI, AiSettingsStore.resolveSelectedProvider(null, useGemini = false))
        assertEquals(AiProviderType.GROK, AiSettingsStore.resolveSelectedProvider("GROK", useGemini = true))
        assertEquals(AiProviderType.OPENAI, AiSettingsStore.resolveSelectedProvider("groq2", useGemini = false))
    }

    @Test
    fun providerStatus_withGroqIsConsistentWithRuntimeSelection() {
        for (groqEnabled in listOf(true, false)) for (groqSelected in listOf(true, false)) for (useGemini in listOf(true, false))
            for (gemini in listOf(true, false)) for (openAi in listOf(true, false)) for (groq in listOf(true, false)) {
                val config = AiRuntimeConfig(
                    useGemini = useGemini, geminiConfigured = gemini, openAiConfigured = openAi,
                    groqEnabled = groqEnabled, groqSelected = groqSelected, groqConfigured = groq,
                )
                val usable = config.selectedProvider() != AiProviderType.NOT_CONFIGURED
                val status = AiProviderStatus.from(config)
                assertEquals("$config", !usable, status.needsAttention)
                if (usable) assertEquals("$config", config.selectedProvider(), status.selected)
            }
        val noKey = AiProviderStatus.from(AiRuntimeConfig(useGemini = true, geminiConfigured = true, groqEnabled = true, groqSelected = true))
        assertEquals(AiProviderStatus.State.SELECTED_WITHOUT_KEY, noKey.state)
        assertEquals(AiProviderType.GROQ, noKey.selected)
        assertEquals(AiProviderType.GEMINI, noKey.alternative)
        assertNull(AiProviderStatus.from(AiRuntimeConfig(groqEnabled = true, groqSelected = true, groqConfigured = true)).alternative)
    }

    @Test
    fun selectorAndDisplayNameOfferGroqOnlyWhenEnabled() {
        assertEquals("Groq", AiProviderStatus.displayName(AiProviderType.GROQ))
        assertEquals(listOf(AiProviderType.GEMINI, AiProviderType.OPENAI), AiProviderStatus.selectableProviders(grokEnabled = false))
        assertEquals(listOf(AiProviderType.GEMINI, AiProviderType.OPENAI, AiProviderType.GROK), AiProviderStatus.selectableProviders(grokEnabled = true))
        assertEquals(
            listOf(AiProviderType.GEMINI, AiProviderType.OPENAI, AiProviderType.GROQ),
            AiProviderStatus.selectableProviders(grokEnabled = false, groqEnabled = true),
        )
        assertEquals(
            listOf(AiProviderType.GEMINI, AiProviderType.OPENAI, AiProviderType.GROK, AiProviderType.GROQ),
            AiProviderStatus.selectableProviders(grokEnabled = true, groqEnabled = true),
        )
    }

    // ---- credentials slot ----

    @Test
    fun credentialSlotIsSeparateFromTheOtherProviders() {
        assertEquals("groq", AiCredentialProvider.GROQ.storageKey)
        assertEquals(AiCredentialProvider.entries.size, AiCredentialProvider.entries.map { it.storageKey }.toSet().size)
        assertEquals("GROQ", GroqUsageTracker.PROVIDER)
    }

    // ---- pricing ----

    @Test
    fun bundledPricing_matchesDocsAndUnknownModelIsZero() {
        val s20 = GroqPricingStore.defaultPricing(AiModelConfig.GROQ_GPT_OSS_20B)
        assertEquals(listOf(BigDecimal("0.075"), BigDecimal("0.30"), BigDecimal("0.037")), listOf(s20.inputUsdPerMillion, s20.outputUsdPerMillion, s20.cachedInputUsdPerMillion))
        val s120 = GroqPricingStore.defaultPricing(AiModelConfig.GROQ_GPT_OSS_120B)
        assertEquals(listOf(BigDecimal("0.15"), BigDecimal("0.60"), BigDecimal("0.075")), listOf(s120.inputUsdPerMillion, s120.outputUsdPerMillion, s120.cachedInputUsdPerMillion))
        val qwen = GroqPricingStore.defaultPricing(AiModelConfig.GROQ_QWEN_38_27B)
        assertEquals(listOf(BigDecimal("0.80"), BigDecimal("4.00"), BigDecimal("0.80")), listOf(qwen.inputUsdPerMillion, qwen.outputUsdPerMillion, qwen.cachedInputUsdPerMillion))
        assertEquals(GroqPricingStore.SOURCE_BUNDLED, qwen.source)
        assertEquals("2026-11-14", qwen.effectiveDate)
        assertEquals(GroqPricingStore.SOURCE_UNKNOWN, GroqPricingStore.defaultPricing("x/y").source)
        assertEquals(0, GroqPricingStore.defaultPricing("x/y").inputUsdPerMillion.signum())
        AiModelConfig.GROQ_SELECTABLE.forEach { assertEquals(GroqPricingStore.SOURCE_BUNDLED, GroqPricingStore.defaultPricing(it).source) }
    }

    // ---- cost ----

    private val gptOss20 get() = GroqPricingStore.defaultPricing(AiModelConfig.GROQ_GPT_OSS_20B)

    @Test
    fun cost_billsCachedAtTheCachedRateAndOutputAsReported() {
        // docs/prompt-caching example: 4641 prompt (4608 cached), 1817 completion.
        val r = GroqCostCalculator.calculate(
            AiUsageMetadata(inputTokens = 4_641, outputTokens = 1_817, totalTokens = 6_458, cachedTokens = 4_608),
            gptOss20,
        )
        assertEquals(4_608L, r.cachedTokens)
        // 33*0.075/M + 4608*0.037/M + 1817*0.30/M = 0.000002475 + 0.000170496 + 0.0005451
        assertEquals(BigDecimal("0.000718071"), r.costUsd)
    }

    @Test
    fun cost_doesNotAddReasoningTokensBecauseCompletionAlreadyIncludesThem() {
        val withReasoning = GroqCostCalculator.calculate(
            AiUsageMetadata(inputTokens = 1_000_000, outputTokens = 1_000_000, totalTokens = 2_000_000, thoughtsTokens = 400_000),
            gptOss20,
        )
        val without = GroqCostCalculator.calculate(AiUsageMetadata(inputTokens = 1_000_000, outputTokens = 1_000_000), gptOss20)
        assertEquals(400_000L, withReasoning.reasoningTokens)
        assertEquals(BigDecimal("0.375000000"), withReasoning.costUsd) // 0.075 + 0.30
        assertEquals(without.costUsdNanos, withReasoning.costUsdNanos)
    }

    @Test
    fun cost_missingUsageIsZeroAndCachedIsClampedToInput() {
        assertEquals(0L, GroqCostCalculator.calculate(AiUsageMetadata(), gptOss20).costUsdNanos)
        val r = GroqCostCalculator.calculate(AiUsageMetadata(inputTokens = 1_000, cachedTokens = 5_000), gptOss20)
        assertEquals(1_000L, r.cachedTokens)
        assertEquals(BigDecimal("0.000037000"), r.costUsd) // all 1k tokens at the cached rate
    }

    @Test
    fun cost_qwenHasNoCacheDiscountAndUsesItsOwnRates() {
        val qwen = GroqPricingStore.defaultPricing(AiModelConfig.GROQ_QWEN_38_27B)
        val r = GroqCostCalculator.calculate(AiUsageMetadata(inputTokens = 1_000_000, outputTokens = 250_000, cachedTokens = 500_000), qwen)
        assertEquals(BigDecimal("1.800000000"), r.costUsd) // 1M*0.80 + 250k*4.00
        assertTrue(r.costUsdNanos > 0)
    }
}
