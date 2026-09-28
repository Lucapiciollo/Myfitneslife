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

    fun calculate(
        calculation: LocalCalculationEngine.Result?,
        checkIn: DailyActivityCheckInEntity?,
    ): Result {
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
        val targetRatio = if (calculation?.targetKcal != null && calculation.targetKcal > 0.0 && operationalTarget != null) {
            operationalTarget / calculation.targetKcal
        } else null
        return Result(
            bmrKcal = calculation?.bmrKcal,
            habitualTdeeKcal = calculation?.tdeeKcal,
            operationalTdeeKcal = operationalTdee,
            profileTargetKcal = calculation?.targetKcal,
            operationalTargetKcal = operationalTarget,
            operationalProteinG = calculation?.proteinG?.let { targetRatio?.times(it) ?: it },
            operationalCarbsG = calculation?.carbsG?.let { targetRatio?.times(it) ?: it },
            operationalFatG = calculation?.fatG?.let { targetRatio?.times(it) ?: it },
            activityAdjustmentKcal = if (status == "PLANNED_WORKOUT") adjustment.coerceIn(0, 225) else 0,
            activityStatus = status,
        )
    }
}
