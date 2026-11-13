package com.myfitai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Offline tests of the Grok runtime wiring (lot 2a): selection, legacy migration, pricing, cost and reasoning effort. */
class GrokRuntimeTest {
    private fun request() = AiStructuredRequest(
        systemPrompt = "SYS", userPrompt = "USER", schemaName = "s",
        schemaJson = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", maxOutputTokens = 100,
    )

    // ---- selection ----

    @Test
    fun defaults_keepGrokOffAndLegacyBehaviourUnchanged() {
        val fresh = AiRuntimeConfig()
        assertFalse(fresh.grokEnabled)
        assertFalse(fresh.grokActive)
        assertEquals(AiProviderType.NOT_CONFIGURED, fresh.selectedProvider())
        // Old installations: only useGemini plus keys.
        assertEquals(AiProviderType.GEMINI, AiRuntimeConfig(useGemini = true, geminiConfigured = true, openAiConfigured = true, grokConfigured = true).selectedProvider())
        assertEquals(AiProviderType.OPENAI, AiRuntimeConfig(useGemini = false, geminiConfigured = true, openAiConfigured = true, grokConfigured = true).selectedProvider())
    }

    @Test
    fun grokChosenButDisabled_fallsBackToThePreviousProvider() {
        val gemini = AiRuntimeConfig(useGemini = true, geminiConfigured = true, grokEnabled = false, grokSelected = true, grokConfigured = true)
        assertEquals(AiProviderType.GEMINI, gemini.selectedProvider())
        val openAi = AiRuntimeConfig(useGemini = false, openAiConfigured = true, grokEnabled = false, grokSelected = true, grokConfigured = true)
        assertEquals(AiProviderType.OPENAI, openAi.selectedProvider())
    }

    @Test
    fun grokIsSelectedOnlyWhenEnabledChosenAndConfigured() {
        val active = AiRuntimeConfig(useGemini = true, geminiConfigured = true, grokEnabled = true, grokSelected = true, grokConfigured = true)
        assertEquals(AiProviderType.GROK, active.selectedProvider())
        // Enabled but not chosen: the historical provider keeps working.
        assertEquals(AiProviderType.GEMINI, active.copy(grokSelected = false).selectedProvider())
        // Chosen and enabled but no key: never a silent switch to another provider.
        assertEquals(AiProviderType.NOT_CONFIGURED, active.copy(grokConfigured = false).selectedProvider())
    }

    @Test
    fun resolveSelectedProvider_migratesLegacyBooleanAndIgnoresGarbage() {
        assertEquals(AiProviderType.GEMINI, AiSettingsStore.resolveSelectedProvider(null, useGemini = true))
        assertEquals(AiProviderType.OPENAI, AiSettingsStore.resolveSelectedProvider(null, useGemini = false))
        assertEquals(AiProviderType.GROK, AiSettingsStore.resolveSelectedProvider("GROK", useGemini = true))
        assertEquals(AiProviderType.OPENAI, AiSettingsStore.resolveSelectedProvider("OPENAI", useGemini = true))
        assertEquals(AiProviderType.GEMINI, AiSettingsStore.resolveSelectedProvider("NOT_CONFIGURED", useGemini = true))
        assertEquals(AiProviderType.OPENAI, AiSettingsStore.resolveSelectedProvider("grok2", useGemini = false))
    }

    @Test
    fun providerStatus_withGrokIsConsistentWithRuntimeSelection() {
        for (grokEnabled in listOf(true, false)) for (grokSelected in listOf(true, false)) for (useGemini in listOf(true, false))
            for (gemini in listOf(true, false)) for (openAi in listOf(true, false)) for (grok in listOf(true, false)) {
                val config = AiRuntimeConfig(useGemini, gemini, openAi, grokEnabled, grokSelected, grok)
                val usable = config.selectedProvider() != AiProviderType.NOT_CONFIGURED
                val status = AiProviderStatus.from(config)
                assertEquals("$config", !usable, status.needsAttention)
                if (usable) assertEquals("$config", config.selectedProvider(), status.selected)
            }
        val noKey = AiProviderStatus.from(AiRuntimeConfig(useGemini = true, geminiConfigured = true, grokEnabled = true, grokSelected = true))
        assertEquals(AiProviderStatus.State.SELECTED_WITHOUT_KEY, noKey.state)
        assertEquals(AiProviderType.GROK, noKey.selected)
        assertEquals(AiProviderType.GEMINI, noKey.alternative)
        assertNull(AiProviderStatus.from(AiRuntimeConfig(grokEnabled = true, grokSelected = true, grokConfigured = true)).alternative)
    }

    // ---- pricing ----

    @Test
    fun bundledPricing_matchesDocsAndUnknownModelIsZero() {
        val p43 = GrokPricingStore.defaultPricing(AiModelConfig.GROK_4_3)
        assertEquals(listOf(BigDecimal("1.25"), BigDecimal("2.50"), BigDecimal("0.20")), listOf(p43.inputUsdPerMillion, p43.outputUsdPerMillion, p43.cachedInputUsdPerMillion))
        val p47 = GrokPricingStore.defaultPricing(AiModelConfig.GROK_4_7)
        assertEquals(listOf(BigDecimal("2.00"), BigDecimal("6.00"), BigDecimal("0.50")), listOf(p47.inputUsdPerMillion, p47.outputUsdPerMillion, p47.cachedInputUsdPerMillion))
        assertEquals(GrokPricingStore.SOURCE_BUNDLED, p47.source)
        assertEquals(GrokPricingStore.SOURCE_UNKNOWN, GrokPricingStore.defaultPricing("grok-x").source)
        assertEquals(0, GrokPricingStore.defaultPricing("grok-x").inputUsdPerMillion.signum())
    }

    // ---- cost ----

    private val p43 get() = GrokPricingStore.defaultPricing(AiModelConfig.GROK_4_3)
    private val p47 get() = GrokPricingStore.defaultPricing(AiModelConfig.GROK_4_7)

    @Test
    fun cost_belowThreshold_billsReasoningAsOutputAndKeepsItSeparate() {
        // grok-4.3: 150k input of which 100k cached, 50k visible + 10k reasoning (total = in + out + reasoning).
        val r = GrokCostCalculator.calculate(
            AiUsageMetadata(inputTokens = 150_000, outputTokens = 50_000, totalTokens = 210_000, thoughtsTokens = 10_000, cachedTokens = 100_000),
            p43,
        )
        assertFalse(r.longContext)
        assertEquals(50_000L, r.outputTokens)
        assertEquals(10_000L, r.reasoningTokens)
        assertEquals(100_000L, r.cachedTokens)
        // 50k*1.25/M + 100k*0.20/M + (50k+10k)*2.50/M = 0.0625 + 0.02 + 0.15
        assertEquals(BigDecimal("0.232500000"), r.costUsd)
    }

    @Test
    fun cost_atOrAboveTwoHundredThousandPromptTokens_doublesEveryRate() {
        val r = GrokCostCalculator.calculate(
            AiUsageMetadata(inputTokens = 200_000, outputTokens = 10_000, totalTokens = 210_000, cachedTokens = 50_000),
            p47,
        )
        assertTrue(r.longContext)
        // 150k*4/M + 50k*1/M + 10k*12/M = 0.6 + 0.05 + 0.12
        assertEquals(BigDecimal("0.770000000"), r.costUsd)
        val below = GrokCostCalculator.calculate(AiUsageMetadata(inputTokens = 199_999, outputTokens = 0), p47)
        assertFalse(below.longContext)
    }

    @Test
    fun cost_doesNotBillReasoningTwiceWhenTotalShowsItIsAlreadyInOutput() {
        // total == input + output while reasoning > 0 -> reasoning is already folded in output_tokens.
        val r = GrokCostCalculator.calculate(
            AiUsageMetadata(inputTokens = 1_000, outputTokens = 60_000, totalTokens = 61_000, thoughtsTokens = 10_000),
            p43,
        )
        // 1k*1.25/M + 60k*2.5/M = 0.00125 + 0.15
        assertEquals(BigDecimal("0.151250000"), r.costUsd)
    }

    @Test
    fun cost_missingUsageIsZeroAndCachedIsClampedToInput() {
        assertEquals(0L, GrokCostCalculator.calculate(AiUsageMetadata(), p43).costUsdNanos)
        val r = GrokCostCalculator.calculate(AiUsageMetadata(inputTokens = 1_000, cachedTokens = 5_000), p43)
        assertEquals(1_000L, r.cachedTokens)
        assertEquals(BigDecimal("0.000200000"), r.costUsd) // all 1k tokens at the cached rate
    }

    // ---- reasoning effort ----

    @Test
    fun requestBody_sendsLowReasoningEffortByDefaultOnBothModels() {
        for (model in AiModelConfig.GROK_SELECTABLE) {
            val body = GrokProvider.buildRequestBody(model, request())
            assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        }
        assertEquals("low", GrokProvider.DEFAULT_REASONING_EFFORT)
    }

    @Test
    fun requestBody_reasoningEffortIsOverridableOmittableAndValidated() {
        assertEquals("high", GrokProvider.buildRequestBody("grok-4.7", request(), "high").getJSONObject("reasoning").getString("effort"))
        assertFalse(GrokProvider.buildRequestBody("grok-4.7", request(), null).has("reasoning"))
        // `none` is documented only for grok-4.3.
        assertEquals("none", GrokProvider.buildRequestBody("grok-4.3", request(), "none").getJSONObject("reasoning").getString("effort"))
        assertThrows(IllegalArgumentException::class.java) { GrokProvider.buildRequestBody("grok-4.7", request(), "none") }
        assertThrows(IllegalArgumentException::class.java) { GrokProvider.buildRequestBody("grok-4.3", request(), "turbo") }
    }
}

