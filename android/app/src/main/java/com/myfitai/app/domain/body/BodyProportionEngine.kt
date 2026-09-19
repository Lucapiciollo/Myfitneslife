package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic, non-diagnostic body proportion analysis.
 *
 * The engine never tries to infer sex/gender and never applies aesthetic "ideal body" formulas.
 * Central ratios are descriptive. The overall balance classification is based only on measured
 * left/right asymmetry, where a comparison is actually objective.
 */
object BodyProportionEngine {

    enum class BalanceStatus {
        BALANCED,
        MILD_IMBALANCE,
        NOTICEABLE_IMBALANCE,
        INSUFFICIENT_DATA,
    }

    data class Ratio(
        val key: String,
        val label: String,
        val value: Float,
        val description: String,
    )

    data class Asymmetry(
        val key: String,
        val label: String,
        val percent: Float,
        val largerSide: String?,
    )

    data class Report(
        val status: BalanceStatus,
        val maxAsymmetryPercent: Float?,
        val ratios: List<Ratio>,
        val asymmetries: List<Asymmetry>,
        val availableMeasurements: Int,
        val note: String,
    )

    fun analyze(measurement: BodyMeasurementEntity?, heightCm: Float?): Report {
        if (measurement == null) return insufficient()

        val ratios = buildList {
            ratio("shoulders_waist", "Spalle / vita", measurement.shouldersCm, measurement.waistCm)?.let(::add)
            ratio("chest_waist", "Torace / vita", measurement.chestCm, measurement.waistCm)?.let(::add)
            ratio("glutes_waist", "Glutei / vita", measurement.glutesCm, measurement.waistCm)?.let(::add)
            ratio("abdomen_waist", "Addome / vita", measurement.abdomenCm, measurement.waistCm)?.let(::add)
            if (heightCm != null && heightCm > 0f) {
                ratio("waist_height", "Vita / altezza", measurement.waistCm, heightCm)?.let(::add)
                ratio("shoulders_height", "Spalle / altezza", measurement.shouldersCm, heightCm)?.let(::add)
                ratio("chest_height", "Torace / altezza", measurement.chestCm, heightCm)?.let(::add)
                ratio("glutes_height", "Glutei / altezza", measurement.glutesCm, heightCm)?.let(::add)
            }
        }

        val asymmetries = listOfNotNull(
            asymmetry("arms", "Braccia", measurement.armLeftCm, measurement.armRightCm),
            asymmetry("thighs", "Cosce", measurement.thighLeftCm, measurement.thighRightCm),
            asymmetry("calves", "Polpacci", measurement.calfLeftCm, measurement.calfRightCm),
        )

        val maxAsymmetry = asymmetries.maxOfOrNull { it.percent }
        val status = when {
            maxAsymmetry == null -> BalanceStatus.INSUFFICIENT_DATA
            maxAsymmetry <= 3f -> BalanceStatus.BALANCED
            maxAsymmetry <= 7f -> BalanceStatus.MILD_IMBALANCE
            else -> BalanceStatus.NOTICEABLE_IMBALANCE
        }

        val values = listOf(
            measurement.chestCm, measurement.waistCm, measurement.abdomenCm,
            measurement.shouldersCm, measurement.glutesCm, measurement.armLeftCm,
            measurement.armRightCm, measurement.thighLeftCm, measurement.thighRightCm,
            measurement.calfLeftCm, measurement.calfRightCm,
        ).count { it != null }

        return Report(
            status = status,
            maxAsymmetryPercent = maxAsymmetry,
            ratios = ratios,
            asymmetries = asymmetries,
            availableMeasurements = values,
            note = "I rapporti sono descrittivi e non rappresentano un ideale estetico o una valutazione medica. Lo stato di equilibrio usa solo le differenze destra/sinistra misurate.",
        )
    }

    private fun ratio(key: String, label: String, numerator: Float?, denominator: Float?): Ratio? {
        if (numerator == null || denominator == null || numerator <= 0f || denominator <= 0f) return null
        val value = numerator / denominator
        return Ratio(
            key = key,
            label = label,
            value = value,
            description = "$label = ${format(value)}",
        )
    }

    private fun asymmetry(key: String, label: String, left: Float?, right: Float?): Asymmetry? {
        if (left == null || right == null || left <= 0f || right <= 0f) return null
        val reference = max(left, right)
        val percent = abs(left - right) / reference * 100f
        val side = when {
            abs(left - right) < 0.05f -> null
            left > right -> "sinistra"
            else -> "destra"
        }
        return Asymmetry(key, label, percent, side)
    }

    private fun insufficient() = Report(
        status = BalanceStatus.INSUFFICIENT_DATA,
        maxAsymmetryPercent = null,
        ratios = emptyList(),
        asymmetries = emptyList(),
        availableMeasurements = 0,
        note = "Dati insufficienti per analizzare le proporzioni.",
    )

    private fun format(value: Float): String = "%.3f".format(java.util.Locale.US, value)
}
