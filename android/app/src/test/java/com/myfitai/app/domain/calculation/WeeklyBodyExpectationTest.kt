package com.myfitai.app.domain.calculation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyBodyExpectationTest {
    @Test
    fun calculatesTheoryFromPlanAndExerciseWithoutCallingItObserved() {
        val result = WeeklyBodyExpectation.calculate(
            maintenanceKcalByDay = List(7) { 2_500 },
            plannedFoodKcalByDay = List(7) { 2_000 },
            exerciseKcalByDay = List(7) { 0 },
        )

        assertTrue(result.available)
        assertEquals(WeeklyBodyExpectation.Source.PLAN, result.source)
        assertEquals(3_500, result.plannedDeficitKcal)
        assertEquals(3_500.0 / 7_700.0 * 0.70, result.expectedFatLossKgMin!!, 0.0001)
        assertTrue(result.caution.contains("piano"))
    }

    @Test
    fun usesConsumptionWhenAtLeastOneRealDayExists() {
        val result = WeeklyBodyExpectation.calculate(
            maintenanceKcalByDay = List(7) { 2_500 },
            plannedFoodKcalByDay = List(7) { 2_000 },
            consumedFoodKcalByDay = listOf(2_300, null, null, null, null, null, null),
        )

        assertEquals(WeeklyBodyExpectation.Source.CONSUMPTION, result.source)
        assertEquals(200, result.plannedDeficitKcal)
    }

    @Test
    fun recompositionIsReachedOnlyWhenFatDropsAndMuscleDoesNotDrop() {
        assertEquals(
            WeeklyBodyExpectation.GoalStatus.REACHED,
            WeeklyBodyExpectation.assessGoal(
                LocalCalculationEngine.Goal.RECOMPOSITION,
                WeeklyBodyExpectation.ObservedProgress(bodyFatDeltaPercentagePoints = -0.5, muscleDeltaKg = 0.3),
            ),
        )
        assertEquals(
            WeeklyBodyExpectation.GoalStatus.REVIEW_REQUIRED,
            WeeklyBodyExpectation.assessGoal(
                LocalCalculationEngine.Goal.RECOMPOSITION,
                WeeklyBodyExpectation.ObservedProgress(bodyFatDeltaPercentagePoints = -0.5, muscleDeltaKg = -0.5),
            ),
        )
    }
}
