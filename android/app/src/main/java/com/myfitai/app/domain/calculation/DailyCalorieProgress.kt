package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.food.NutritionRecoveryTargetEngine

object DailyCalorieProgress {
    enum class Status { NO_TARGET, EMPTY, IN_PROGRESS, COMPLETE, EXCEEDED }

    data class State(
        val targetKcal: Int?,
        val consumedKcal: Int,
        val percent: Int,
        val exceededKcal: Int,
        val status: Status,
    )

    fun calculate(targetKcal: Int?, consumedKcal: Int): State {
        val safeConsumed = consumedKcal.coerceAtLeast(0)
        val target = targetKcal?.takeIf { it > 0 }
        if (target == null) return State(null, safeConsumed, 0, 0, Status.NO_TARGET)
        val exceeded = (safeConsumed - target).coerceAtLeast(0)
        return State(
            targetKcal = target,
            consumedKcal = safeConsumed,
            percent = ((safeConsumed.toDouble() / target) * 100.0).toInt().coerceIn(0, 100),
            exceededKcal = exceeded,
            status = when {
                safeConsumed <= 0 -> Status.EMPTY
                exceeded > 0 -> Status.EXCEEDED
                safeConsumed >= target -> Status.COMPLETE
                else -> Status.IN_PROGRESS
            },
        )
    }

    fun recoveryRemainingPercent(state: NutritionRecoveryTargetEngine.State?): Int {
        if (state == null || state.budgetBeforeKcal <= 0) return 0
        val initialBudget = state.budgetBeforeKcal + state.plannedRecoveryKcal
        return ((state.budgetAfterPlannedKcal.toDouble() / initialBudget) * 100.0).toInt().coerceIn(0, 100)
    }
}
