package com.myfitai.app.ai

import android.content.Context
import java.math.BigDecimal
import java.math.RoundingMode

/** Per-1M-token rates for the BELOW-200k-prompt tier. Above the threshold [GrokCostCalculator] applies the documented x2 tier. */
data class GrokPricing(
    val model: String,
    val inputUsdPerMillion: BigDecimal,
    val outputUsdPerMillion: BigDecimal,
    val cachedInputUsdPerMillion: BigDecimal,
    val source: String,
    val effectiveDate: String,
)

data class GrokCalculatedCost(
    val inputTokens: Long,
    /** Visible output tokens as reported by xAI (`output_tokens`), reasoning excluded. */
    val outputTokens: Long,
    /** Reasoning tokens, kept separate from [outputTokens] and billed at the output rate. */
    val reasoningTokens: Long,
    val cachedTokens: Long,
    /** True when the prompt reached the long-context threshold and every rate was doubled. */
    val longContext: Boolean,
    val costUsdNanos: Long,
) {
    val costUsd: BigDecimal
        get() = BigDecimal(costUsdNanos).movePointLeft(9)
}

/**
 * xAI billing (docs.x.ai/developers/models, 13/11/2026):
 * - a request whose prompt reaches 200k tokens is billed at the higher tier "for all tokens in the request": input, cached
 *   input and output rates are all doubled (grok-4.3 1.25/0.20/2.50 -> 2.50/0.40/5.00; grok-4.7 2.00/0.50/6.00 -> 4.00/1.00/12.00);
 * - cached tokens are a subset of the input tokens;
 * - reasoning tokens are billed as output. xAI reports them IN ADDITION to `output_tokens` (total = input + output + reasoning),
 *   so they are added to the billable output; if `total_tokens` equals input + output while reasoning > 0 the provider has
 *   already folded reasoning into output and it is not billed twice.
 */
object GrokCostCalculator {
    const val LONG_CONTEXT_PROMPT_TOKENS = 200_000L
    private val LONG_CONTEXT_MULTIPLIER = BigDecimal(2)
    private val ONE_MILLION = BigDecimal("1000000")
    private val ONE_BILLION = BigDecimal("1000000000")

    fun calculate(usage: AiUsageMetadata, pricing: GrokPricing): GrokCalculatedCost {
        val inputTokens = (usage.inputTokens ?: 0L).coerceAtLeast(0L)
        val outputTokens = (usage.outputTokens ?: 0L).coerceAtLeast(0L)
        val reasoningTokens = (usage.thoughtsTokens ?: 0L).coerceAtLeast(0L)
        val cachedTokens = (usage.cachedTokens ?: 0L).coerceAtLeast(0L).coerceAtMost(inputTokens)

        val reasoningAlreadyInOutput = reasoningTokens > 0L && usage.totalTokens != null && usage.totalTokens == inputTokens + outputTokens
        val billedOutput = if (reasoningAlreadyInOutput) outputTokens else outputTokens + reasoningTokens
        val longContext = inputTokens >= LONG_CONTEXT_PROMPT_TOKENS
        val tier = if (longContext) LONG_CONTEXT_MULTIPLIER else BigDecimal.ONE

        val total = tokenCost(inputTokens - cachedTokens, pricing.inputUsdPerMillion.multiply(tier))
            .add(tokenCost(cachedTokens, pricing.cachedInputUsdPerMillion.multiply(tier)))
            .add(tokenCost(billedOutput, pricing.outputUsdPerMillion.multiply(tier)))

        return GrokCalculatedCost(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            cachedTokens = cachedTokens,
            longContext = longContext,
            costUsdNanos = total.multiply(ONE_BILLION).setScale(0, RoundingMode.HALF_UP).longValueExact(),
        )
    }

    private fun tokenCost(tokens: Long, usdPerMillion: BigDecimal): BigDecimal =
        BigDecimal(tokens).multiply(usdPerMillion).divide(ONE_MILLION, 12, RoundingMode.HALF_UP)
}

class GrokPricingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pricingFor(model: String): GrokPricing {
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

    /** Manual rates are the BELOW-200k-prompt rates, like the bundled ones: the long-context x2 tier is still applied on top. */
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
        const val SOURCE_BUNDLED = "BUNDLED_GROK_PRICING"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_UNKNOWN = "UNKNOWN_MODEL"
        const val SOURCE_LONG_CONTEXT_SUFFIX = "+LONG_CONTEXT_X2"
        private const val EFFECTIVE_DATE = "2026-11-13"
        private const val PREFS_NAME = "grok_pricing"
        private const val KEY_MANUAL = "manual"
        private const val KEY_INPUT = "input_usd_per_million"
        private const val KEY_OUTPUT = "output_usd_per_million"
        private const val KEY_CACHE = "cache_usd_per_million"

        /** Bundled list prices (<200k prompt tier), docs.x.ai/developers/models/grok-4.3 and grok-4.7 on 13/11/2026. */
        internal fun defaultPricing(model: String): GrokPricing = when (model) {
            AiModelConfig.GROK_4_3 -> bundled(model, "1.25", "2.50", "0.20")
            AiModelConfig.GROK_4_7 -> bundled(model, "2.00", "6.00", "0.50")
            else -> GrokPricing(model, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, SOURCE_UNKNOWN, EFFECTIVE_DATE)
        }

        private fun bundled(model: String, input: String, output: String, cache: String) =
            GrokPricing(model, BigDecimal(input), BigDecimal(output), BigDecimal(cache), SOURCE_BUNDLED, EFFECTIVE_DATE)
    }
}
