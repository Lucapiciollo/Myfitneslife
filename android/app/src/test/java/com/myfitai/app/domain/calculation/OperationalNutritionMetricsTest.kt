package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.LocalCalculationEngine.ActivityLevel
import com.myfitai.app.domain.calculation.LocalCalculationEngine.BiologicalSex
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home, Food and BIA Coach show the numbers of the profile: TDEE = BMR x activity-level factor,
 * target = TDEE x goal factor. One value for every day: nothing here depends on check-ins or workouts.
 */
class OperationalNutritionMetricsTest {
    private fun profile(level: ActivityLevel, goal: Goal = Goal.WEIGHT_LOSS) = LocalCalculationEngine.calculate(
        LocalCalculationEngine.Input(
            weightKg = 89.5, heightCm = 186.0, ageYears = 43, biologicalSex = BiologicalSex.MALE,
            bodyFatPercent = 19.9, activityLevel = level, goal = goal,
        ),
    )

    @Test
    fun metricsAreExactlyTheProfileNumbers() {
        val calculation = LocalCalculationEngine.Result(
            bmi = null, bmrKcal = 2000.0, tdeeKcal = 3000.0, targetKcal = 2850.0,
            proteinG = 180.0, fatG = 72.0, carbsG = 370.0, waistHeightRatio = null, bmrMethod = null,
        )

        val metrics = OperationalNutritionMetrics.calculate(calculation)

        assertEquals(2000.0, metrics.bmrKcal!!, 0.0)
        assertEquals(3000.0, metrics.tdeeKcal!!, 0.0)
        assertEquals(2850.0, metrics.targetKcal!!, 0.0)
        assertEquals(180.0, metrics.proteinG!!, 0.0)
        assertEquals(72.0, metrics.fatG!!, 0.0)
        assertEquals(370.0, metrics.carbsG!!, 0.0)
    }

    @Test
    fun withoutACheckInTheTdeeIsTheHabitualOneNotTheRestOne() {
        // Before A1 a missing check-in meant "rest day": TDEE = BMR x 1,2 and a lower target.
        val calculation = profile(ActivityLevel.MODERATE)

        val metrics = OperationalNutritionMetrics.calculate(calculation)

        assertEquals(calculation.bmrKcal!! * 1.55, metrics.tdeeKcal!!, 0.001)
        assertTrue(metrics.tdeeKcal!! > calculation.bmrKcal!! * 1.2 + 100.0)
        assertEquals(calculation.targetKcal!!, metrics.targetKcal!!, 0.0)
    }

    @Test
    fun theActivityLevelOfTheProfileMovesTdeeAndTarget() {
        val moderate = OperationalNutritionMetrics.calculate(profile(ActivityLevel.MODERATE))
        val veryActive = OperationalNutritionMetrics.calculate(profile(ActivityLevel.VERY_ACTIVE))
        val sedentary = OperationalNutritionMetrics.calculate(profile(ActivityLevel.SEDENTARY))

        assertEquals(moderate.bmrKcal!!, veryActive.bmrKcal!!, 0.0)
        assertEquals(veryActive.bmrKcal!! * 1.725, veryActive.tdeeKcal!!, 0.001)
        assertTrue(veryActive.tdeeKcal!! > moderate.tdeeKcal!!)
        assertTrue(veryActive.targetKcal!! > moderate.targetKcal!!)
        assertTrue(moderate.tdeeKcal!! > sedentary.tdeeKcal!!)
        assertTrue(moderate.targetKcal!! > sedentary.targetKcal!!)
    }

    @Test
    fun macrosKeepThePolicyOfTheProfileTarget() {
        // K6 real case: woman 78 kg, sedentary, weight loss. Protein is 30% of the profile kcal, not 2,0 g/kg.
        val calculation = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 78.0, heightCm = 164.0, ageYears = 62,
                biologicalSex = BiologicalSex.FEMALE,
                activityLevel = ActivityLevel.SEDENTARY,
                goal = Goal.WEIGHT_LOSS,
            ),
        )
        assertEquals(1360.68, calculation.targetKcal!!, 0.01)

        val metrics = OperationalNutritionMetrics.calculate(calculation)

        assertEquals(0.075 * 1360.68, metrics.proteinG!!, 0.01)
        assertEquals(calculation.proteinG!!, metrics.proteinG!!, 0.0)
        assertEquals(calculation.carbsG!!, metrics.carbsG!!, 0.0)
        assertEquals(calculation.fatG!!, metrics.fatG!!, 0.0)
    }

    @Test
    fun missingCalculationGivesNoNumbers() {
        val metrics = OperationalNutritionMetrics.calculate(null)

        assertNull(metrics.bmrKcal)
        assertNull(metrics.tdeeKcal)
        assertNull(metrics.targetKcal)
        assertNull(metrics.proteinG)
        assertNull(metrics.carbsG)
        assertNull(metrics.fatG)
    }
}
