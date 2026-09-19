package com.myfitai.app.domain.calculation

import kotlin.math.abs
import kotlin.math.max

/**
 * Motore locale deterministico. Nessun valore numerico viene delegato all'LLM.
 * Se i dati minimi non sono sufficienti, il risultato resta null invece di inventare stime.
 */
object LocalCalculationEngine {

    enum class BiologicalSex { MALE, FEMALE }

    enum class ActivityLevel(val multiplier: Double) {
        SEDENTARY(1.20),
        LIGHT(1.375),
        MODERATE(1.55),
        VERY_ACTIVE(1.725),
        EXTREME(1.90),
    }

    enum class Goal {
        RECOMPOSITION,
        WEIGHT_LOSS,
        MAINTENANCE,
        MUSCLE_GAIN,
        PERFORMANCE,
    }

    enum class TrendDirection { DOWN, STABLE, UP, UNKNOWN }

    enum class RecompositionState {
        FAVORABLE,
        FAT_LOSS_WITH_STABLE_MUSCLE,
        MUSCLE_GAIN_WITH_STABLE_FAT,
        STABLE,
        MIXED,
        NOT_ENOUGH_DATA,
    }

    data class Input(
        val weightKg: Double?,
        val heightCm: Double?,
        val ageYears: Int?,
        val biologicalSex: BiologicalSex? = null,
        val bodyFatPercent: Double? = null,
        val activityLevel: ActivityLevel?,
        val goal: Goal?,
        val waistCm: Double? = null,
    )

    data class Result(
        val bmi: Double?,
        val bmrKcal: Double?,
        val tdeeKcal: Double?,
        val targetKcal: Double?,
        val proteinG: Double?,
        val fatG: Double?,
        val carbsG: Double?,
        val waistHeightRatio: Double?,
        val bmrMethod: String?,
    )

    data class MacroTargets(
        val proteinG: Double,
        val fatG: Double,
        val carbsG: Double,
    )

    data class TimedValue(
        val timestamp: Long,
        val value: Double,
    )

    data class TrendStats(
        val count: Int,
        val average: Double?,
        val first: Double?,
        val latest: Double?,
        val delta: Double?,
        val direction: TrendDirection,
    )

    fun calculate(input: Input): Result {
        val weight = input.weightKg.validPositive()
        val height = input.heightCm.validPositive()

        val bmi = if (weight != null && height != null) {
            weight / ((height / 100.0) * (height / 100.0))
        } else null

        val bmrWithMethod = calculateBmr(input, weight, height)
        val bmr = bmrWithMethod?.first
        val tdee = if (bmr != null && input.activityLevel != null) bmr * input.activityLevel.multiplier else null
        val targetKcal = if (tdee != null && input.goal != null) tdee * goalEnergyFactor(input.goal) else null

        val macros = if (targetKcal != null && weight != null && input.goal != null) {
            calculateMacrosForTarget(targetKcal, weight, input.goal)
        } else null

        val waistHeightRatio = if (input.waistCm.validPositive() != null && height != null) {
            input.waistCm!! / height
        } else null

        return Result(
            bmi = bmi,
            bmrKcal = bmr,
            tdeeKcal = tdee,
            targetKcal = targetKcal,
            proteinG = macros?.proteinG,
            fatG = macros?.fatG,
            carbsG = macros?.carbsG,
            waistHeightRatio = waistHeightRatio,
            bmrMethod = bmrWithMethod?.second,
        )
    }

    /**
     * Priorità: Katch-McArdle se è disponibile una % di grasso plausibile.
     * Altrimenti Mifflin-St Jeor solo se sesso biologico, età, peso e altezza sono disponibili.
     */
    private fun calculateBmr(input: Input, weight: Double?, height: Double?): Pair<Double, String>? {
        val bodyFat = input.bodyFatPercent?.takeIf { it in 2.0..70.0 }
        if (weight != null && bodyFat != null) {
            val leanMassKg = weight * (1.0 - bodyFat / 100.0)
            return (370.0 + 21.6 * leanMassKg) to "KATCH_MCARDLE"
        }

        val age = input.ageYears?.takeIf { it in 14..120 }
        val sex = input.biologicalSex
        if (weight == null || height == null || age == null || sex == null) return null

        val base = 10.0 * weight + 6.25 * height - 5.0 * age
        val sexAdjustment = if (sex == BiologicalSex.MALE) 5.0 else -161.0
        return (base + sexAdjustment) to "MIFFLIN_ST_JEOR"
    }

    /** Fattore iniziale deterministico rispetto al TDEE. */
    fun goalEnergyFactor(goal: Goal): Double = when (goal) {
        Goal.RECOMPOSITION -> 0.95
        Goal.WEIGHT_LOSS -> 0.85
        Goal.MAINTENANCE -> 1.0
        Goal.MUSCLE_GAIN -> 1.10
        Goal.PERFORMANCE -> 1.0
    }

    /**
     * Ricalcola i macro a partire da un target calorico già deciso localmente.
     * Serve anche quando l'AdaptiveNutritionTargetEngine applica una piccola correzione al target.
     */
    fun calculateMacrosForTarget(targetKcal: Double, weightKg: Double, goal: Goal): MacroTargets {
        require(targetKcal.isFinite() && targetKcal > 0.0) { "TARGET_KCAL_INVALID" }
        require(weightKg.isFinite() && weightKg > 0.0) { "WEIGHT_INVALID" }

        val proteinPerKg = when (goal) {
            Goal.WEIGHT_LOSS, Goal.RECOMPOSITION, Goal.MUSCLE_GAIN -> 2.0
            Goal.MAINTENANCE, Goal.PERFORMANCE -> 1.8
        }
        val fatPerKg = when (goal) {
            Goal.PERFORMANCE -> 0.9
            else -> 0.8
        }

        val proteinG = weightKg * proteinPerKg
        val fatG = weightKg * fatPerKg
        val committedKcal = proteinG * 4.0 + fatG * 9.0
        val carbsG = max(0.0, (targetKcal - committedKcal) / 4.0)
        return MacroTargets(proteinG, fatG, carbsG)
    }

    fun trend(values: List<TimedValue>, stableThreshold: Double = 0.05): TrendStats {
        val ordered = values.sortedWith(compareBy<TimedValue> { it.timestamp }.thenBy { it.value })
        if (ordered.isEmpty()) {
            return TrendStats(0, null, null, null, null, TrendDirection.UNKNOWN)
        }

        val first = ordered.first().value
        val latest = ordered.last().value
        val delta = if (ordered.size >= 2) latest - first else null
        val direction = when {
            delta == null -> TrendDirection.UNKNOWN
            abs(delta) <= stableThreshold -> TrendDirection.STABLE
            delta < 0.0 -> TrendDirection.DOWN
            else -> TrendDirection.UP
        }

        return TrendStats(
            count = ordered.size,
            average = ordered.map { it.value }.average(),
            first = first,
            latest = latest,
            delta = delta,
            direction = direction,
        )
    }

    /**
     * Classificazione descrittiva, non causale. Usa solo delta tra due periodi comparabili.
     */
    fun classifyRecomposition(
        bodyFatDeltaPercentPoints: Double?,
        muscleMassDeltaKg: Double?,
        fatStableThreshold: Double = 0.3,
        muscleStableThreshold: Double = 0.3,
    ): RecompositionState {
        if (bodyFatDeltaPercentPoints == null || muscleMassDeltaKg == null) return RecompositionState.NOT_ENOUGH_DATA

        val fatStable = abs(bodyFatDeltaPercentPoints) <= fatStableThreshold
        val muscleStable = abs(muscleMassDeltaKg) <= muscleStableThreshold

        return when {
            bodyFatDeltaPercentPoints < -fatStableThreshold && muscleMassDeltaKg > muscleStableThreshold -> RecompositionState.FAVORABLE
            bodyFatDeltaPercentPoints < -fatStableThreshold && muscleStable -> RecompositionState.FAT_LOSS_WITH_STABLE_MUSCLE
            fatStable && muscleMassDeltaKg > muscleStableThreshold -> RecompositionState.MUSCLE_GAIN_WITH_STABLE_FAT
            fatStable && muscleStable -> RecompositionState.STABLE
            else -> RecompositionState.MIXED
        }
    }

    private fun Double?.validPositive(): Double? = this?.takeIf { it.isFinite() && it > 0.0 }
}

object ProfileCalculationMapper {
    fun activity(value: String?): LocalCalculationEngine.ActivityLevel? = when (value?.trim()?.lowercase()) {
        "sedentario" -> LocalCalculationEngine.ActivityLevel.SEDENTARY
        "leggermente attivo" -> LocalCalculationEngine.ActivityLevel.LIGHT
        "moderatamente attivo" -> LocalCalculationEngine.ActivityLevel.MODERATE
        "molto attivo" -> LocalCalculationEngine.ActivityLevel.VERY_ACTIVE
        "estremamente attivo" -> LocalCalculationEngine.ActivityLevel.EXTREME
        else -> null
    }

    fun goal(value: String?): LocalCalculationEngine.Goal? = when (value?.trim()?.lowercase()) {
        "ricomposizione" -> LocalCalculationEngine.Goal.RECOMPOSITION
        "dimagrimento" -> LocalCalculationEngine.Goal.WEIGHT_LOSS
        "mantenimento" -> LocalCalculationEngine.Goal.MAINTENANCE
        "aumento massa muscolare" -> LocalCalculationEngine.Goal.MUSCLE_GAIN
        "performance" -> LocalCalculationEngine.Goal.PERFORMANCE
        else -> null
    }
}
