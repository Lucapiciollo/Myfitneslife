package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.data.local.entity.NutritionRecoveryEventEntity
import com.myfitai.app.data.local.entity.NutritionRecoveryWithdrawalEntity
import kotlin.math.min

object NutritionRecoveryTargetEngine {
    const val DEFAULT_RECOVERY_WINDOW_DAYS = 7
    const val MAX_DAILY_RECOVERY_PERCENT = 0.10

    data class State(
        val budgetBeforeKcal: Int,
        val plannedRecoveryKcal: Int,
        val effectiveTargetKcal: Int,
        val budgetAfterPlannedKcal: Int,
        val confirmedRecoveryKcal: Int,
        val budgetAfterConfirmedKcal: Int,
        val remainingDays: Int,
        val exerciseKcal: Int = 0,
        val foodAllowanceKcal: Int = 0,
    )

    fun calculate(
        adaptiveTargetKcal: Int,
        activeEvents: List<NutritionRecoveryEventEntity>,
        currentEpochDay: Long,
        existingWithdrawal: NutritionRecoveryWithdrawalEntity? = null,
        plannedBeforeKcal: Int = 0,
        exerciseKcal: Int = 0,
    ): State {
        val active = activeEvents.filter { it.status == STATUS_ACTIVE && it.expiresEpochDay >= currentEpochDay }
        val safeExerciseKcal = exerciseKcal.coerceAtLeast(0)
        val budget = (active.sumOf { it.remainingKcal } - plannedBeforeKcal - safeExerciseKcal).coerceAtLeast(0)
        val foodAllowance = adaptiveTargetKcal + safeExerciseKcal
        val remainingDays = active.maxOfOrNull { it.expiresEpochDay - currentEpochDay + 1 }?.toInt()?.coerceAtLeast(1) ?: 0
        val planned = existingWithdrawal?.plannedRecoveryKcal ?: min(
            budget / remainingDays.coerceAtLeast(1),
            (foodAllowance * MAX_DAILY_RECOVERY_PERCENT).toInt(),
        ).coerceIn(0, budget)
        val confirmed = existingWithdrawal?.confirmedRecoveryKcal ?: 0
        return State(budget, planned, foodAllowance - planned, (budget - planned).coerceAtLeast(0), confirmed, (budget - confirmed).coerceAtLeast(0), remainingDays, safeExerciseKcal, foodAllowance)
    }

    const val STATUS_ACTIVE = "ACTIVE"
    const val STATUS_RECOVERED = "RECOVERED"
    const val SOURCE_CHEAT = "CHEAT"
}
