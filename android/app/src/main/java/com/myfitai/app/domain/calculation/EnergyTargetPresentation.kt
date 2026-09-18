package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.food.NutritionRecoveryTargetEngine

object EnergyTargetPresentation {
    enum class Mode { DEFICIT, MAINTENANCE, SURPLUS, RECOVERY_ADJUSTED, INSUFFICIENT_DATA }

    data class State(
        val mode: Mode,
        val tdeeKcal: Int? = null,
        val baseTdeeKcal: Int? = null,
        val exerciseKcal: Int = 0,
        val normalTargetKcal: Int? = null,
        val effectiveTargetKcal: Int? = null,
        val differenceKcal: Int? = null,
        val recovery: NutritionRecoveryTargetEngine.State? = null,
        val goal: LocalCalculationEngine.Goal? = null,
    )

    fun build(
        calculation: LocalCalculationEngine.Result?,
        recovery: NutritionRecoveryTargetEngine.State? = null,
        goal: LocalCalculationEngine.Goal? = null,
    ): State {
        val tdee = calculation?.tdeeKcal?.takeIf { it.isFinite() && it > 0 }?.toInt()
        val baseTdee = calculation?.baseTdeeKcal?.takeIf { it.isFinite() && it > 0 }?.toInt()
        val exercise = calculation?.exerciseKcal?.coerceAtLeast(0) ?: 0
        val normal = calculation?.targetKcal?.takeIf { it.isFinite() && it > 0 }?.toInt()
        val effective = recovery?.effectiveTargetKcal ?: normal
        val difference = if (tdee != null && normal != null) normal - tdee else null
        val baseMode = when {
            tdee == null || normal == null -> Mode.INSUFFICIENT_DATA
            difference!! < -ENERGY_CLASSIFICATION_TOLERANCE_KCAL -> Mode.DEFICIT
            difference > ENERGY_CLASSIFICATION_TOLERANCE_KCAL -> Mode.SURPLUS
            else -> Mode.MAINTENANCE
        }
        return State(if (recovery?.plannedRecoveryKcal ?: 0 > 0) Mode.RECOVERY_ADJUSTED else baseMode, tdee, baseTdee, exercise, normal, effective, difference, recovery, goal)
    }

    private const val ENERGY_CLASSIFICATION_TOLERANCE_KCAL = 50
}
