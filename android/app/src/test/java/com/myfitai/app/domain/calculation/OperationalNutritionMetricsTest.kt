package com.myfitai.app.domain.calculation

import com.myfitai.app.data.local.entity.DailyActivityCheckInEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationalNutritionMetricsTest {
    @Test
    fun restAndWorkoutUseTheSameOperationalEngine() {
        val calculation = LocalCalculationEngine.Result(
            bmi = null,
            bmrKcal = 2000.0,
            tdeeKcal = 3000.0,
            targetKcal = 2850.0,
            proteinG = null,
            fatG = null,
            carbsG = null,
            waistHeightRatio = null,
            bmrMethod = null,
        )

        val rest = OperationalNutritionMetrics.calculate(calculation, checkIn("REST", 0))
        val workout = OperationalNutritionMetrics.calculate(calculation, checkIn("PLANNED_WORKOUT", 140))

        assertEquals(2400.0, rest.operationalTdeeKcal!!, 0.001)
        assertEquals(3140.0, workout.operationalTdeeKcal!!, 0.001)
        assertEquals(2850.0 * 2400.0 / 3000.0, rest.operationalTargetKcal!!, 0.001)
        assertEquals(2850.0 * 3140.0 / 3000.0, workout.operationalTargetKcal!!, 0.001)
    }

    @Test
    fun legacyMacrosKeepProteinAndFatPerKgAndMoveOnlyCarbohydrates() {
        val calculation = LocalCalculationEngine.Result(
            bmi = null, bmrKcal = 2000.0, tdeeKcal = 3000.0, targetKcal = 2850.0,
            proteinG = 180.0, fatG = 72.0, carbsG = 370.0, waistHeightRatio = null, bmrMethod = null,
        )

        val rest = OperationalNutritionMetrics.calculate(calculation, checkIn("REST", 0))

        assertEquals(180.0, rest.operationalProteinG!!, 0.001)
        assertEquals(72.0, rest.operationalFatG!!, 0.001)
        val restTarget = rest.operationalTargetKcal!!
        assertEquals((restTarget - 180.0 * 4 - 72.0 * 9) / 4, rest.operationalCarbsG!!, 0.001)
    }

    @Test
    fun programDayReplacesTheLegacyEstimateWithThePlanEngine() {
        val calculation = LocalCalculationEngine.Result(
            bmi = null, bmrKcal = 1918.0, tdeeKcal = 2973.0, targetKcal = 2527.0,
            proteinG = 179.0, fatG = 71.6, carbsG = 291.0, waistHeightRatio = null, bmrMethod = null,
        )
        val days = TrainingEnergyPlanner.plan(
            dates = listOf(java.time.LocalDate.of(2026, 9, 14), java.time.LocalDate.of(2026, 9, 16)),
            inputs = TrainingEnergyPlanner.Inputs(
                bmrKcal = 1918.0,
                weightKg = 89.5,
                goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                activityBasis = TrainingEnergyPlanner.ActivityBasis.LEGACY_INCLUDES_TRAINING,
                program = TrainingProgram(setOf(java.time.DayOfWeek.WEDNESDAY)),
            ),
        )!!

        val rest = OperationalNutritionMetrics.calculate(calculation, null, days[0])
        val training = OperationalNutritionMetrics.calculate(calculation, null, days[1])

        assertEquals(days[0].energy.tdeeKcal, rest.operationalTdeeKcal!!, 0.001)
        assertEquals(days[1].baseTargets.kcal, training.operationalTargetKcal!!, 0.001)
        assertEquals("REST", rest.activityStatus)
        assertEquals("PLANNED_WORKOUT", training.activityStatus)
        assertEquals(days[1].energy.exerciseKcal, training.activityAdjustmentKcal)
        assertEquals("protein does not scale with calories", rest.operationalProteinG, training.operationalProteinG)
        assertEquals(2527.0, rest.profileTargetKcal!!, 0.001)
        assertEquals(2973.0, training.habitualTdeeKcal!!, 0.001)
    }

    private fun checkIn(status: String, adjustment: Int) = DailyActivityCheckInEntity(
        profileId = 1L,
        dateEpochDay = 1L,
        status = status,
        durationMinutes = null,
        intensity = null,
        adjustmentKcal = adjustment,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}
