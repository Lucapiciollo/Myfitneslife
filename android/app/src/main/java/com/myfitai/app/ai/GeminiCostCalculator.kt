package com.myfitai.app.ai

import java.math.BigDecimal
import java.math.RoundingMode

data class GeminiPricing(
    val model: String,
    val inputUsdPerMillion: BigDecimal,
    val outputUsdPerMillion: BigDecimal,
    val cachedInputUsdPerMillion: BigDecimal,
    val source: String,
    val effectiveDate: String,
)

data class GeminiCalculatedCost(
    val inputTokens: Long,
    val outputTokens: Long,
    val thoughtsTokens: Long,
    val cachedTokens: Long,
    val costUsdNanos: Long,
) {
    val costUsd: BigDecimal
        get() = BigDecimal(costUsdNanos).movePointLeft(9)
}

object GeminiCostCalculator {
    private val ONE_MILLION = BigDecimal("1000000")
    private val ONE_BILLION = BigDecimal("1000000000")

    fun calculate(usage: AiUsageMetadata, pricing: GeminiPricing): GeminiCalculatedCost {
        val inputTokens = (usage.inputTokens ?: 0L).coerceAtLeast(0L)
        val outputTokens = (usage.outputTokens ?: 0L).coerceAtLeast(0L)
        val thoughtsTokens = (usage.thoughtsTokens ?: 0L).coerceAtLeast(0L)
        val cachedTokens = (usage.cachedTokens ?: 0L).coerceAtLeast(0L)

        // Gemini reports cached input as part of promptTokenCount. Charge cached tokens at the
        // cache rate and only the remaining prompt tokens at the regular input rate.
        val regularInputTokens = (inputTokens - cachedTokens).coerceAtLeast(0L)
        // Gemini pricing states that thinking tokens are billed at the output rate.
        val billedOutputTokens = outputTokens + thoughtsTokens

        val inputCost = tokenCost(regularInputTokens, pricing.inputUsdPerMillion)
        val cachedCost = tokenCost(cachedTokens, pricing.cachedInputUsdPerMillion)
        val outputCost = tokenCost(billedOutputTokens, pricing.outputUsdPerMillion)
        val total = inputCost.add(cachedCost).add(outputCost)

        val nanos = total.multiply(ONE_BILLION)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

        return GeminiCalculatedCost(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            thoughtsTokens = thoughtsTokens,
            cachedTokens = cachedTokens,
            costUsdNanos = nanos,
        )
    }

    private fun tokenCost(tokens: Long, usdPerMillion: BigDecimal): BigDecimal =
        BigDecimal(tokens).multiply(usdPerMillion).divide(ONE_MILLION, 12, RoundingMode.HALF_UP)
}
