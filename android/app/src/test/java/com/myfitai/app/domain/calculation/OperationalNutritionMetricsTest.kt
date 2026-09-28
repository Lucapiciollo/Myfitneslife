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
