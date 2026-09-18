package com.myfitai.app.domain.calculation

import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic weekly expectation, never a promise or a medical prediction.
 * The app separates planned energy from consumed energy and explicitly marks the source.
 */
object WeeklyBodyExpectation {
    private const val KCAL_PER_KG_FAT = 7_700.0

    enum class Source { PLAN, CONSUMPTION, INSUFFICIENT_DATA }

    data class Result(
        val available: Boolean,
        val source: Source,
        val plannedDeficitKcal: Int? = null,
        val expectedFatLossKgMin: Double? = null,
        val expectedFatLossKgMax: Double? = null,
        val expectedWeightChangeKgMin: Double? = null,
        val expectedWeightChangeKgMax: Double? = null,
        val caution: String = "",
        val goalStatus: GoalStatus = GoalStatus.NEEDS_MORE_DATA,
        val observedWeightDeltaKg: Double? = null,
        val observedFatDeltaPercentagePoints: Double? = null,
        val observedMuscleDeltaKg: Double? = null,
        val observedWaistDeltaCm: Double? = null,
    )

    enum class GoalStatus { IN_PROGRESS, REACHED, NEEDS_MORE_DATA, REVIEW_REQUIRED }

    data class ObservedProgress(
        val weightDeltaKg: Double? = null,
        val bodyFatDeltaPercentagePoints: Double? = null,
        val muscleDeltaKg: Double? = null,
        val waistDeltaCm: Double? = null,
    )

    fun assessGoal(goal: LocalCalculationEngine.Goal?, progress: ObservedProgress, minimumMeasurements: Int = 2): GoalStatus {
        if (listOf(progress.weightDeltaKg, progress.bodyFatDeltaPercentagePoints, progress.muscleDeltaKg, progress.waistDeltaCm).count { it != null } == 0) return GoalStatus.NEEDS_MORE_DATA
        return when (goal) {
            LocalCalculationEngine.Goal.WEIGHT_LOSS -> if ((progress.weightDeltaKg ?: 0.0) < -0.2 || (progress.bodyFatDeltaPercentagePoints ?: 0.0) < -0.2) GoalStatus.REACHED else GoalStatus.IN_PROGRESS
            LocalCalculationEngine.Goal.MUSCLE_GAIN, LocalCalculationEngine.Goal.PERFORMANCE -> if ((progress.muscleDeltaKg ?: 0.0) > 0.2) GoalStatus.REACHED else GoalStatus.IN_PROGRESS
            LocalCalculationEngine.Goal.RECOMPOSITION -> when {
                (progress.bodyFatDeltaPercentagePoints ?: 0.0) < -0.2 && (progress.muscleDeltaKg ?: 0.0) >= -0.2 -> GoalStatus.REACHED
                progress.bodyFatDeltaPercentagePoints != null && progress.muscleDeltaKg != null -> GoalStatus.REVIEW_REQUIRED
                else -> GoalStatus.NEEDS_MORE_DATA
            }
            LocalCalculationEngine.Goal.MAINTENANCE -> if (kotlin.math.abs(progress.weightDeltaKg ?: 99.0) <= 0.5) GoalStatus.REACHED else GoalStatus.REVIEW_REQUIRED
            null -> GoalStatus.NEEDS_MORE_DATA
        }
    }

    fun calculate(
        maintenanceKcalByDay: List<Int?>,
        plannedFoodKcalByDay: List<Int?>,
        exerciseKcalByDay: List<Int> = emptyList(),
        consumedFoodKcalByDay: List<Int?>? = null,
    ): Result {
        val days = max(maintenanceKcalByDay.size, plannedFoodKcalByDay.size)
        if (days == 0 || maintenanceKcalByDay.size < days || plannedFoodKcalByDay.size < days) return Result(false, Source.INSUFFICIENT_DATA, caution = "Dati energetici insufficienti")
        val hasConsumption = consumedFoodKcalByDay != null && consumedFoodKcalByDay.any { it != null }
        val food = if (hasConsumption) consumedFoodKcalByDay!! else plannedFoodKcalByDay
        val dailyDeficit = (0 until days).mapNotNull { index ->
            val maintenance = maintenanceKcalByDay[index] ?: return@mapNotNull null
            val foodKcal = food.getOrNull(index) ?: return@mapNotNull null
            val exercise = exerciseKcalByDay.getOrNull(index) ?: 0
            // Exercise increases expenditure; food remains a separate observed/planned quantity.
            maintenance + exercise - foodKcal
        }
        if (dailyDeficit.isEmpty()) return Result(false, Source.INSUFFICIENT_DATA, caution = "Completa i dati energetici della settimana")
        val totalDeficit = dailyDeficit.sum().coerceAtLeast(0)
        // Energy balance is only a theoretical conversion: not every kcal comes from fat,
        // especially over one week. Keep the range explicit instead of presenting a false exact
        // fat prediction. The 70%-120% band absorbs normal partitioning and measurement noise.
        val centralFat = totalDeficit / KCAL_PER_KG_FAT
        val minFat = centralFat * 0.70
        val maxFat = centralFat * 1.20
        return Result(
            available = true,
            source = if (hasConsumption) Source.CONSUMPTION else Source.PLAN,
            plannedDeficitKcal = totalDeficit,
            expectedFatLossKgMin = minFat,
            expectedFatLossKgMax = maxFat,
            expectedWeightChangeKgMin = -maxFat,
            expectedWeightChangeKgMax = -minFat,
            caution = if (hasConsumption) "Stima basata sui consumi registrati; acqua e glicogeno possono modificare il peso osservato." else "Stima teorica basata sul piano; non equivale a una previsione certa del peso.",
        )
    }
}
