package com.myfitai.app.ai

import java.math.BigDecimal
import java.math.RoundingMode

data class OpenAiPricing(
    val model: String,
    val inputUsdPerMillion: BigDecimal,
    val outputUsdPerMillion: BigDecimal,
    val cachedInputUsdPerMillion: BigDecimal,
    val source: String,
    val effectiveDate: String,
)

data class OpenAiCalculatedCost(
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cachedTokens: Long,
    val costUsdNanos: Long,
) {
    val costUsd: BigDecimal
        get() = BigDecimal(costUsdNanos).movePointLeft(9)
}

object OpenAiCostCalculator {
    private val ONE_MILLION = BigDecimal("1000000")
    private val ONE_BILLION = BigDecimal("1000000000")

    fun calculate(usage: AiUsageMetadata, pricing: OpenAiPricing): OpenAiCalculatedCost {
        val inputTokens = (usage.inputTokens ?: 0L).coerceAtLeast(0L)
        val outputTokens = (usage.outputTokens ?: 0L).coerceAtLeast(0L)
        val reasoningTokens = (usage.thoughtsTokens ?: 0L).coerceAtLeast(0L)
        val cachedTokens = (usage.cachedTokens ?: 0L).coerceAtLeast(0L)

        // Responses API reports cached input as a subset of input_tokens.
        val regularInputTokens = (inputTokens - cachedTokens).coerceAtLeast(0L)
        // output_tokens already includes any reasoning tokens, so never add reasoningTokens again.
        val inputCost = tokenCost(regularInputTokens, pricing.inputUsdPerMillion)
        val cachedCost = tokenCost(cachedTokens, pricing.cachedInputUsdPerMillion)
        val outputCost = tokenCost(outputTokens, pricing.outputUsdPerMillion)
        val total = inputCost.add(cachedCost).add(outputCost)

        val nanos = total.multiply(ONE_BILLION)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

        return OpenAiCalculatedCost(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            cachedTokens = cachedTokens,
            costUsdNanos = nanos,
        )
    }

    private fun tokenCost(tokens: Long, usdPerMillion: BigDecimal): BigDecimal =
        BigDecimal(tokens).multiply(usdPerMillion).divide(ONE_MILLION, 12, RoundingMode.HALF_UP)
}
