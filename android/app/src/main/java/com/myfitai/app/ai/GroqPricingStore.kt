package com.myfitai.app.ai

import android.content.Context
import java.math.BigDecimal
import java.math.RoundingMode

/** Per-1M-token list rates of a Groq model (no long-context tier is documented). */
data class GroqPricing(
    val model: String,
    val inputUsdPerMillion: BigDecimal,
    val outputUsdPerMillion: BigDecimal,
    val cachedInputUsdPerMillion: BigDecimal,
    val source: String,
    val effectiveDate: String,
)

data class GroqCalculatedCost(
    val inputTokens: Long,
    /** `completion_tokens` as reported by Groq: it already includes any reasoning tokens. */
    val outputTokens: Long,
    /** Reasoning tokens, informational only (already inside [outputTokens]; never billed twice). */
    val reasoningTokens: Long,
    val cachedTokens: Long,
    val costUsdNanos: Long,
) {
    val costUsd: BigDecimal
        get() = BigDecimal(costUsdNanos).movePointLeft(9)
}

/**
 * Groq billing (console.groq.com/docs/models, /docs/model/<id>, /docs/prompt-caching, 14/11/2026):
 * - cached input tokens (prompt caching, only gpt-oss models) get a 50% discount and are a subset of `prompt_tokens`;
 * - `total_tokens` = prompt + completion: reasoning tokens are part of `completion_tokens`, so output is billed as reported.
 */
object GroqCostCalculator {
    private val ONE_MILLION = BigDecimal("1000000")
    private val ONE_BILLION = BigDecimal("1000000000")

    fun calculate(usage: AiUsageMetadata, pricing: GroqPricing): GroqCalculatedCost {
        val inputTokens = (usage.inputTokens ?: 0L).coerceAtLeast(0L)
        val outputTokens = (usage.outputTokens ?: 0L).coerceAtLeast(0L)
        val reasoningTokens = (usage.thoughtsTokens ?: 0L).coerceAtLeast(0L)
        val cachedTokens = (usage.cachedTokens ?: 0L).coerceAtLeast(0L).coerceAtMost(inputTokens)

        val total = tokenCost(inputTokens - cachedTokens, pricing.inputUsdPerMillion)
            .add(tokenCost(cachedTokens, pricing.cachedInputUsdPerMillion))
            .add(tokenCost(outputTokens, pricing.outputUsdPerMillion))

        return GroqCalculatedCost(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            cachedTokens = cachedTokens,
            costUsdNanos = total.multiply(ONE_BILLION).setScale(0, RoundingMode.HALF_UP).longValueExact(),
        )
    }

    private fun tokenCost(tokens: Long, usdPerMillion: BigDecimal): BigDecimal =
        BigDecimal(tokens).multiply(usdPerMillion).divide(ONE_MILLION, 12, RoundingMode.HALF_UP)
}

class GroqPricingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pricingFor(model: String): GroqPricing {
        val defaults = defaultPricing(model)
        if (!isManual(model)) return defaults
        return defaults.copy(
            inputUsdPerMillion = readDecimal(model, KEY_INPUT, defaults.inputUsdPerMillion),
            outputUsdPerMillion = readDecimal(model, KEY_OUTPUT, defaults.outputUsdPerMillion),
            cachedInputUsdPerMillion = readDecimal(model, KEY_CACHE, defaults.cachedInputUsdPerMillion),
            source = SOURCE_MANUAL,
        )
    }

    fun isManual(model: String): Boolean = prefs.getBoolean(key(model, KEY_MANUAL), false)

    fun setManual(model: String, inputUsdPerMillion: BigDecimal, outputUsdPerMillion: BigDecimal, cachedInputUsdPerMillion: BigDecimal) {
        require(inputUsdPerMillion.signum() >= 0)
        require(outputUsdPerMillion.signum() >= 0)
        require(cachedInputUsdPerMillion.signum() >= 0)
        prefs.edit()
            .putBoolean(key(model, KEY_MANUAL), true)
            .putString(key(model, KEY_INPUT), inputUsdPerMillion.stripTrailingZeros().toPlainString())
            .putString(key(model, KEY_OUTPUT), outputUsdPerMillion.stripTrailingZeros().toPlainString())
            .putString(key(model, KEY_CACHE), cachedInputUsdPerMillion.stripTrailingZeros().toPlainString())
            .apply()
    }

    fun restoreDefaults(model: String) {
        prefs.edit()
            .remove(key(model, KEY_MANUAL))
            .remove(key(model, KEY_INPUT))
            .remove(key(model, KEY_OUTPUT))
            .remove(key(model, KEY_CACHE))
            .apply()
    }

    private fun readDecimal(model: String, field: String, fallback: BigDecimal): BigDecimal =
        prefs.getString(key(model, field), null)?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: fallback

    private fun key(model: String, field: String): String = "${model.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.$field"

    companion object {
        const val SOURCE_BUNDLED = "BUNDLED_GROQ_PRICING"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_UNKNOWN = "UNKNOWN_MODEL"
        private const val EFFECTIVE_DATE = "2026-11-14"
        private const val PREFS_NAME = "groq_pricing"
        private const val KEY_MANUAL = "manual"
        private const val KEY_INPUT = "input_usd_per_million"
        private const val KEY_OUTPUT = "output_usd_per_million"
        private const val KEY_CACHE = "cache_usd_per_million"

        /**
         * Bundled list prices, console.groq.com/docs/model/<id> on 14/11/2026 (USD per 1M tokens).
         * gpt-oss: cached input shown on the model pages (0.037 / 0.075 = the documented 50% discount, rounded as published).
         * qwen3.8-27b: no prompt caching and no cached price documented, so cached tokens are billed at the input rate.
         */
        internal fun defaultPricing(model: String): GroqPricing = when (model) {
            AiModelConfig.GROQ_GPT_OSS_20B -> bundled(model, "0.075", "0.30", "0.037")
            AiModelConfig.GROQ_GPT_OSS_120B -> bundled(model, "0.15", "0.60", "0.075")
            AiModelConfig.GROQ_QWEN_38_27B -> bundled(model, "0.80", "4.00", "0.80")
            else -> GroqPricing(model, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, SOURCE_UNKNOWN, EFFECTIVE_DATE)
        }

        private fun bundled(model: String, input: String, output: String, cache: String) =
            GroqPricing(model, BigDecimal(input), BigDecimal(output), BigDecimal(cache), SOURCE_BUNDLED, EFFECTIVE_DATE)
    }
}
