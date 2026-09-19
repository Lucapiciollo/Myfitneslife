package com.myfitai.app.ai

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiCostCalculatorTest {
    private val pricing = GeminiPricing(
        model = "test",
        inputUsdPerMillion = BigDecimal("1.50"),
        outputUsdPerMillion = BigDecimal("9.00"),
        cachedInputUsdPerMillion = BigDecimal("0.15"),
        source = "TEST",
        effectiveDate = "2026-09-15",
    )

    @Test
    fun calculatesInputOutputThinkingAndCachedCost() {
        val result = GeminiCostCalculator.calculate(
            AiUsageMetadata(
                inputTokens = 1_000_000,
                outputTokens = 100_000,
                thoughtsTokens = 50_000,
                cachedTokens = 200_000,
            ),
            pricing,
        )

        // 800k regular input = 1.20; 200k cached = 0.03; 150k output+thinking = 1.35.
        assertEquals(BigDecimal("2.580000000"), result.costUsd)
    }

    @Test
    fun zeroOrMissingUsageCostsZero() {
        val result = GeminiCostCalculator.calculate(AiUsageMetadata(), pricing)
        assertEquals(BigDecimal("0E-9"), result.costUsd)
    }

    @Test
    fun cachedTokensNeverMakeRegularInputNegative() {
        val result = GeminiCostCalculator.calculate(
            AiUsageMetadata(inputTokens = 10, cachedTokens = 20),
            pricing,
        )
        assertEquals(BigDecimal("0.000003000"), result.costUsd)
    }
}
