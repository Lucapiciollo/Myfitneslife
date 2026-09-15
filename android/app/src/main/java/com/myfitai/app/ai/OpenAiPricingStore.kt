package com.myfitai.app.ai

import android.content.Context
import java.math.BigDecimal

/** Local non-secret OpenAI pricing snapshot with optional per-model manual override. */
class OpenAiPricingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pricingFor(model: String): OpenAiPricing {
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

    fun setManual(
        model: String,
        inputUsdPerMillion: BigDecimal,
        outputUsdPerMillion: BigDecimal,
        cachedInputUsdPerMillion: BigDecimal,
    ) {
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

    private fun defaultPricing(model: String): OpenAiPricing = when (model) {
        AiModelConfig.OPENAI -> OpenAiPricing(
            model = model,
            inputUsdPerMillion = BigDecimal("0.15"),
            outputUsdPerMillion = BigDecimal("0.60"),
            cachedInputUsdPerMillion = BigDecimal("0.075"),
            source = SOURCE_BUNDLED,
            effectiveDate = "2026-09-15",
        )
        else -> OpenAiPricing(
            model = model,
            inputUsdPerMillion = BigDecimal.ZERO,
            outputUsdPerMillion = BigDecimal.ZERO,
            cachedInputUsdPerMillion = BigDecimal.ZERO,
            source = SOURCE_UNKNOWN,
            effectiveDate = "2026-09-15",
        )
    }

    private fun key(model: String, field: String): String = "${model.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.$field"

    companion object {
        const val SOURCE_BUNDLED = "BUNDLED_OPENAI_PRICING"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_UNKNOWN = "UNKNOWN_MODEL"

        private const val PREFS_NAME = "openai_pricing"
        private const val KEY_MANUAL = "manual"
        private const val KEY_INPUT = "input_usd_per_million"
        private const val KEY_OUTPUT = "output_usd_per_million"
        private const val KEY_CACHE = "cache_usd_per_million"
    }
}
