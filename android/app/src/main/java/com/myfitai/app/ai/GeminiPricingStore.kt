package com.myfitai.app.ai

import android.content.Context
import java.math.BigDecimal

/**
 * Non-secret local pricing configuration. Google does not expose a zero-setup public pricing
 * endpoint suitable for a distributed BYOK Android client, so defaults are versioned in-app and
 * can be overridden per model by the user.
 */
class GeminiPricingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pricingFor(model: String): GeminiPricing {
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
        prefs.edit().remove(key(model, KEY_MANUAL)).remove(key(model, KEY_INPUT)).remove(key(model, KEY_OUTPUT)).remove(key(model, KEY_CACHE)).apply()
    }

    private fun readDecimal(model: String, field: String, fallback: BigDecimal): BigDecimal =
        prefs.getString(key(model, field), null)?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: fallback

    private fun defaultPricing(model: String): GeminiPricing = when (model) {
        AiModelConfig.GEMINI_36_FLASH -> pricing(model, "0.75", "3.75", "0.075", "2026-09-15 promo through 2026-12-31")
        AiModelConfig.GEMINI_PRIMARY -> pricing(model, "1.50", "9.00", "0.15", "2026-09-15")
        AiModelConfig.GEMINI_35_FLASH_LITE -> pricing(model, "0.30", "2.50", "0.03", "2026-09-15")
        AiModelConfig.GEMINI_FALLBACK -> pricing(model, "0.25", "1.50", "0.025", "2026-09-15")
        AiModelConfig.GEMINI_25_FLASH_LITE -> pricing(model, "0.10", "0.40", "0.01", "2026-09-15")
        else -> GeminiPricing(model, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, SOURCE_UNKNOWN, "2026-09-15")
    }

    private fun pricing(model: String, input: String, output: String, cache: String, effectiveDate: String) = GeminiPricing(
        model = model,
        inputUsdPerMillion = BigDecimal(input),
        outputUsdPerMillion = BigDecimal(output),
        cachedInputUsdPerMillion = BigDecimal(cache),
        source = SOURCE_BUNDLED,
        effectiveDate = effectiveDate,
    )

    private fun key(model: String, field: String): String = "${model.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.$field"

    companion object {
        const val SOURCE_BUNDLED = "BUNDLED_GOOGLE_PRICING"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_UNKNOWN = "UNKNOWN_MODEL"
        private const val PREFS_NAME = "gemini_pricing"
        private const val KEY_MANUAL = "manual"
        private const val KEY_INPUT = "input_usd_per_million"
        private const val KEY_OUTPUT = "output_usd_per_million"
        private const val KEY_CACHE = "cache_usd_per_million"
    }
}
