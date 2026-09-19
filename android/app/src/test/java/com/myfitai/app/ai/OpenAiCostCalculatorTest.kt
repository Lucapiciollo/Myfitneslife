package com.myfitai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class OpenAiCostCalculatorTest {
    private val pricing = OpenAiPricing(
        model = "gpt-4o-mini",
        inputUsdPerMillion = BigDecimal("0.15"),
        outputUsdPerMillion = BigDecimal("0.60"),
        cachedInputUsdPerMillion = BigDecimal("0.075"),
        source = "TEST",
        effectiveDate = "2026-09-15",
    )

    @Test
    fun calculate_usesCachedSubsetAndDoesNotDoubleBillReasoning() {
        val result = OpenAiCostCalculator.calculate(
            usage = AiUsageMetadata(
                inputTokens = 1_000_000,
                outputTokens = 500_000,
                totalTokens = 1_500_000,
                thoughtsTokens = 100_000,
                cachedTokens = 200_000,
            ),
            pricing = pricing,
        )

        assertEquals(1_000_000L, result.inputTokens)
        assertEquals(500_000L, result.outputTokens)
        assertEquals(100_000L, result.reasoningTokens)
        assertEquals(200_000L, result.cachedTokens)
        // 800k * .15/M + 200k * .075/M + 500k * .60/M = $0.435
        assertEquals(BigDecimal("0.435000000"), result.costUsd)
    }

    @Test
    fun calculate_treatsMissingUsageFieldsAsZero() {
        val result = OpenAiCostCalculator.calculate(AiUsageMetadata(), pricing)
        assertEquals(BigDecimal("0E-9"), result.costUsd)
        assertEquals(0L, result.inputTokens)
        assertEquals(0L, result.outputTokens)
        assertEquals(0L, result.cachedTokens)
    }
}
