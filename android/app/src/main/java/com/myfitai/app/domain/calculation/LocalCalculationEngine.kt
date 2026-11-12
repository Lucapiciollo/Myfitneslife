package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.body.BiaNoiseThresholds
import kotlin.math.abs
import kotlin.math.max

/**
 * Motore locale deterministico. Nessun valore numerico viene delegato all'LLM.
 * Se i dati minimi non sono sufficienti, il risultato resta null invece di inventare stime.
 */
object LocalCalculationEngine {

    /** Intervallo di età (anni compiuti) per cui le formule metaboliche sono considerate affidabili. */
    const val MIN_AGE_YEARS = 14
    const val MAX_AGE_YEARS = 120

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
        /**
         * Peso della STESSA rilevazione BIA da cui arriva [bodyFatPercent]. Se presente la massa magra di
         * Katch-McArdle usa questo peso e non il peso piu recente con una percentuale di grasso vecchia.
         */
        val bodyFatReferenceWeightKg: Double? = null,
        /** Data (epoch millis) della rilevazione da cui arriva [bodyFatPercent], solo per dichiararla nel risultato. */
        val bodyFatMeasuredAtEpochMillis: Long? = null,
        /** Motivo per cui il grasso non e stato usato (BODY_FAT_MISSING / BODY_FAT_TOO_OLD), solo per dichiararlo. */
        val bodyFatFallbackReason: String? = null,
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
        /** Target prima del minimo di sicurezza (null se non calcolabile). */
        val unclampedTargetKcal: Double? = targetKcal,
        /** Minimo di sicurezza applicabile ([SafetyCalorieFloor]); null se il BMR non e calcolabile. */
        val minimumKcal: Double? = null,
        /** True se il target e stato portato al minimo di sicurezza. */
        val floorApplied: Boolean = false,
        /** Data della rilevazione del grasso considerata per il BMR (usata se KATCH, scartata se MIFFLIN con motivo). */
        val bmrBodyFatMeasuredAtEpochMillis: Long? = null,
        /** Perche si e usato Mifflin invece di Katch-McArdle; null se Katch o nessun grasso disponibile senza motivo. */
        val bmrFallbackReason: String? = null,
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
        val unclampedTarget = if (tdee != null && input.goal != null) tdee * goalEnergyFactor(input.goal) else null
        val minimumKcal = if (bmr != null) SafetyCalorieFloor.minimumKcal(bmr, input.biologicalSex) else null
        val clamp = if (unclampedTarget != null && SafetyCalorieFloor.appliesTo(input.goal)) {
            SafetyCalorieFloor.clamp(unclampedTarget, minimumKcal)
        } else null
        val targetKcal = clamp?.kcal ?: unclampedTarget

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
            unclampedTargetKcal = unclampedTarget,
            minimumKcal = minimumKcal,
            floorApplied = clamp?.applied == true,
            bmrBodyFatMeasuredAtEpochMillis = input.bodyFatMeasuredAtEpochMillis,
            bmrFallbackReason = if (bmrWithMethod?.second == "MIFFLIN_ST_JEOR") input.bodyFatFallbackReason else null,
        )
    }

    /**
     * Priorità: Katch-McArdle se è disponibile una % di grasso plausibile.
     * Altrimenti Mifflin-St Jeor solo se sesso biologico, età, peso e altezza sono disponibili.
     */
    private fun calculateBmr(input: Input, weight: Double?, height: Double?): Pair<Double, String>? {
        val bodyFat = input.bodyFatPercent?.takeIf { it in 2.0..70.0 }
        if (weight != null && bodyFat != null) {
            // Peso e grasso della stessa rilevazione; senza peso di riferimento resta il peso corrente.
            val referenceWeight = input.bodyFatReferenceWeightKg.validPositive() ?: weight
            val leanMassKg = referenceWeight * (1.0 - bodyFat / 100.0)
            return (370.0 + 21.6 * leanMassKg) to "KATCH_MCARDLE"
        }

        val age = input.ageYears?.takeIf { it in MIN_AGE_YEARS..MAX_AGE_YEARS }
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
     * Le soglie di stabilita sono il rumore BIA dichiarato in [BiaNoiseThresholds]: una variazione
     * sotto soglia e "nessuna variazione affidabile" e quindi stabile, non un segnale.
     */
    fun classifyRecomposition(
        bodyFatDeltaPercentPoints: Double?,
        muscleMassDeltaKg: Double?,
        fatStableThreshold: Double = BiaNoiseThresholds.BODY_FAT_POINTS,
        muscleStableThreshold: Double = BiaNoiseThresholds.LEAN_MASS_KG,
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
    /** Unica mappatura del sesso salvato nel profilo (stringa libera) verso l'enum del motore. */
    fun sex(value: String?): LocalCalculationEngine.BiologicalSex? = when (value?.trim()?.lowercase()) {
        "maschio", "male", "m" -> LocalCalculationEngine.BiologicalSex.MALE
        "femmina", "female", "f" -> LocalCalculationEngine.BiologicalSex.FEMALE
        else -> null
    }

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
