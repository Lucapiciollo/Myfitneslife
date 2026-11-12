package com.myfitai.app.domain.body

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Deterministic body-composition scenarios and fat-change estimate of the BIA Progress Coach.
 *
 * The model never computes these numbers: the app calculates them here, sends them as `SCENARI_APP` /
 * `GRASSO_PERSO_STIMA_APP` and the model only comments on them. Pure object: no Android, Room or clock.
 *
 * Reversible assumptions (to confirm with the product owner):
 * - the non-fat mass is `weight - fat mass` and stays constant in every scenario;
 * - scenario targets go down in steps of [STEP_POINTS] percentage points from the current body fat and never
 *   go below the realistic floor of [scenarioFloorPercent];
 * - a fat change is "reliable" only when it exceeds the BIA noise thresholds ([BiaNoiseThresholds]), the
 *   fat mass in kg being compared with the weight threshold.
 */
object BiaScenarioCalculator {
    const val MAX_SCENARIOS = 3
    const val STEP_POINTS = 2.0

    /** Minimum distance, in percentage points, between the current body fat and the first target. */
    private const val MIN_DISTANCE_POINTS = 1.0

    /** Values of one BIA reading; every field may be missing. */
    data class Composition(
        val weightKg: Double? = null,
        val bodyFatPercent: Double? = null,
        val fatMassKg: Double? = null,
        val leanMassKg: Double? = null,
    )

    /** Consistent weight / fat / non-fat triple derived from a [Composition]. */
    data class Resolved(val weightKg: Double, val fatMassKg: Double, val leanMassKg: Double) {
        val bodyFatPercent: Double get() = fatMassKg / weightKg * 100.0
    }

    data class Scenario(
        /** Stable reference quoted by the model: S1, S2, S3. */
        val id: String,
        val targetBodyFatPercent: Double,
        val targetWeightKg: Double,
        val fatToLoseKg: Double,
    ) {
        /** Weight change needed to reach the scenario, always <= 0 here (fat mass lost, lean mass kept). */
        val weightChangeKg: Double get() = -fatToLoseKg
    }

    enum class FatStatus { FAT_DOWN, FAT_UP, WITHIN_NOISE, NOT_AVAILABLE }

    data class FatChange(
        val status: FatStatus,
        val fatMassDeltaKg: Double? = null,
        val weightDeltaKg: Double? = null,
        val bodyFatDeltaPoints: Double? = null,
        /** Only when [status] is [FatStatus.FAT_DOWN]: a positive estimate, never a measured fact. */
        val estimatedFatLostKg: Double? = null,
    )

    /** Plausible target body fat for the sex (reversible assumption): women 18-45, men 6-35, unknown the union. */
    fun plausibleBodyFatRange(sex: String?): ClosedFloatingPointRange<Double> = when (normalizedSex(sex)) {
        Sex.FEMALE -> 18.0..45.0
        Sex.MALE -> 6.0..35.0
        null -> 6.0..45.0
    }

    /** Realistic lowest scenario target: higher than the plausible minimum, so the app never suggests extreme leanness. */
    fun scenarioFloorPercent(sex: String?): Double = when (normalizedSex(sex)) {
        Sex.FEMALE -> 20.0
        Sex.MALE -> 10.0
        null -> 12.0
    }

    /** Derives a consistent triple, or null when weight, fat mass and non-fat mass cannot all be known. */
    fun resolve(value: Composition): Resolved? {
        val givenWeight = value.weightKg?.takeIf { it.isFinite() && it > 0.0 }
        val givenFat = value.fatMassKg?.takeIf { it.isFinite() && it > 0.0 }
        val percent = value.bodyFatPercent?.takeIf { it.isFinite() && it > 0.0 && it < 100.0 }
        val lean = value.leanMassKg?.takeIf { it.isFinite() && it > 0.0 }
        val weight = givenWeight ?: if (givenFat != null && lean != null) givenFat + lean else null
        if (weight == null) return null
        val fat = givenFat ?: percent?.let { weight * it / 100.0 } ?: lean?.let { weight - it }
        if (fat == null || fat >= weight) return null
        return Resolved(weight, fat, weight - fat)
    }

    /** Up to [MAX_SCENARIOS] scenarios below the current body fat; empty when data are missing or no target is realistic. */
    fun scenarios(value: Composition, sex: String?): List<Scenario> {
        val current = resolve(value) ?: return emptyList()
        val base = current.bodyFatPercent.roundToLong().toDouble()
        val range = plausibleBodyFatRange(sex)
        val floor = scenarioFloorPercent(sex).coerceAtLeast(range.start)
        return (1..MAX_SCENARIOS).mapNotNull { index ->
            val target = base - STEP_POINTS * index
            if (target < floor || target > range.endInclusive || current.bodyFatPercent - target < MIN_DISTANCE_POINTS) return@mapNotNull null
            val targetWeight = round1(current.leanMassKg / (1.0 - target / 100.0))
            val toLose = round1(round1(current.weightKg) - targetWeight)
            if (toLose <= 0.0) return@mapNotNull null
            Scenario("S$index", target, targetWeight, toLose)
        }.mapIndexed { position, scenario -> scenario.copy(id = "S${position + 1}") }
    }

    /**
     * Estimated fat change between two readings. Without both fat masses nothing is claimed
     * ([FatStatus.NOT_AVAILABLE]); inside the noise thresholds the answer is [FatStatus.WITHIN_NOISE], never a loss.
     */
    fun fatChange(previous: Composition, current: Composition, degraded: Boolean = false): FatChange {
        val before = fatMassOf(previous)
        val after = fatMassOf(current)
        if (before == null || after == null) return FatChange(FatStatus.NOT_AVAILABLE)
        val fatDelta = after - before
        val weightDelta = if (previous.weightKg != null && current.weightKg != null) current.weightKg - previous.weightKg else null
        val pointsDelta = percentOf(previous, before)?.let { p -> percentOf(current, after)?.let { c -> c - p } }
        val reliable = BiaNoiseThresholds.isReliableChange("weightKg", fatDelta, degraded) &&
            (pointsDelta == null || BiaNoiseThresholds.isReliableChange("bodyFatPercent", pointsDelta, degraded))
        val status = when {
            !reliable -> FatStatus.WITHIN_NOISE
            fatDelta < 0.0 -> FatStatus.FAT_DOWN
            else -> FatStatus.FAT_UP
        }
        return FatChange(
            status = status,
            fatMassDeltaKg = fatDelta,
            weightDeltaKg = weightDelta,
            bodyFatDeltaPoints = pointsDelta,
            estimatedFatLostKg = if (status == FatStatus.FAT_DOWN) abs(fatDelta) else null,
        )
    }

    private fun fatMassOf(value: Composition): Double? {
        value.fatMassKg?.takeIf { it.isFinite() && it > 0.0 }?.let { return it }
        val weight = value.weightKg?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val percent = value.bodyFatPercent?.takeIf { it.isFinite() && it > 0.0 && it < 100.0 } ?: return null
        return weight * percent / 100.0
    }

    private fun percentOf(value: Composition, fatMass: Double): Double? {
        value.bodyFatPercent?.takeIf { it.isFinite() && it > 0.0 && it < 100.0 }?.let { return it }
        val weight = value.weightKg?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return fatMass / weight * 100.0
    }

    // ---- Formatting: one place, so the context sent to the model and the UI show the same digits --------------

    fun round1(value: Double): Double = (value * 10.0).roundToLong() / 10.0

    /** One decimal with a dot, as used in the model context (`70.1`). */
    fun contextNumber(value: Double): String = String.format(Locale.US, "%.1f", round1(value))

    /** Whole percent with a dot-free integer when exact (`32`), otherwise one decimal. */
    fun contextPercent(value: Double): String =
        if (floor(value) == value) value.toLong().toString() else contextNumber(value)

    /** One decimal with a comma, as shown to the user (`70,1`). */
    fun displayNumber(value: Double): String = String.format(Locale.ITALIAN, "%.1f", round1(value))

    fun displayPercent(value: Double): String =
        if (floor(value) == value) value.toLong().toString() else displayNumber(value)

    private enum class Sex { FEMALE, MALE }

    private fun normalizedSex(sex: String?): Sex? = when (sex?.trim()?.lowercase()) {
        "femmina", "female", "f" -> Sex.FEMALE
        "maschio", "male", "m" -> Sex.MALE
        else -> null
    }
}
