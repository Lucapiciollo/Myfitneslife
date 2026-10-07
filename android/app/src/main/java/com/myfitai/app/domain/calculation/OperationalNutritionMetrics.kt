package com.myfitai.app.domain.calculation

import com.myfitai.app.data.local.entity.DailyActivityCheckInEntity

/** Shared source of truth for nutrition metrics rendered by Home and Food. */
object OperationalNutritionMetrics {
    data class Result(
        val bmrKcal: Double?,
        val habitualTdeeKcal: Double?,
        val operationalTdeeKcal: Double?,
        val profileTargetKcal: Double?,
        val operationalTargetKcal: Double?,
        val operationalProteinG: Double?,
        val operationalCarbsG: Double?,
        val operationalFatG: Double?,
        val activityAdjustmentKcal: Int,
        val activityStatus: String,
    )

    /**
     * @param programDay the day computed by the shared day-energy engine when the profile has a training
     * program; null keeps the legacy habitual-TDEE behaviour.
     */
    fun calculate(
        calculation: LocalCalculationEngine.Result?,
        checkIn: DailyActivityCheckInEntity?,
        programDay: TrainingEnergyPlanner.Day? = null,
    ): Result {
        if (programDay != null) {
            return Result(
                bmrKcal = calculation?.bmrKcal ?: programDay.energy.bmrKcal,
                habitualTdeeKcal = calculation?.tdeeKcal,
                operationalTdeeKcal = programDay.energy.tdeeKcal,
                profileTargetKcal = calculation?.targetKcal,
                operationalTargetKcal = programDay.baseTargets.kcal,
                operationalProteinG = programDay.baseTargets.proteinG,
                operationalCarbsG = programDay.baseTargets.carbsG,
                operationalFatG = programDay.baseTargets.fatG,
                activityAdjustmentKcal = programDay.energy.exerciseKcal,
                activityStatus = if (programDay.resolution.isTraining) "PLANNED_WORKOUT" else "REST",
            )
        }
        val status = checkIn?.status ?: "REST"
        val adjustment = checkIn?.adjustmentKcal ?: 0
        val operationalTdee = DailyActivityCheckInEngine.effectiveTdeeKcal(
            bmrKcal = calculation?.bmrKcal,
            habitualTdeeKcal = calculation?.tdeeKcal,
            status = status,
            adjustmentKcal = adjustment,
        )
        val operationalTarget = if (operationalTdee != null && calculation?.targetKcal != null && calculation.tdeeKcal != null && calculation.tdeeKcal > 0.0) {
            calculation.targetKcal * operationalTdee / calculation.tdeeKcal
        } else null
        // Protein and fat are per kilogram of body weight and do not move with the day's calories:
        // carbohydrates absorb the difference.
        val proteinG = calculation?.proteinG
        val fatG = calculation?.fatG
        val carbsG = if (operationalTarget != null && proteinG != null && fatG != null) {
            kotlin.math.max(0.0, (operationalTarget - proteinG * 4.0 - fatG * 9.0) / 4.0)
        } else calculation?.carbsG
        return Result(
            bmrKcal = calculation?.bmrKcal,
            habitualTdeeKcal = calculation?.tdeeKcal,
            operationalTdeeKcal = operationalTdee,
            profileTargetKcal = calculation?.targetKcal,
            operationalTargetKcal = operationalTarget,
            operationalProteinG = proteinG,
            operationalCarbsG = carbsG,
            operationalFatG = fatG,
            activityAdjustmentKcal = if (status == "PLANNED_WORKOUT") adjustment.coerceIn(0, 225) else 0,
            activityStatus = status,
        )
    }
}
