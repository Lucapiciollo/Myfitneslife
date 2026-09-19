package com.myfitai.app.domain.calculation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyBodyExpectationTest {
    private val monday = 21_000L

    @Test
    fun completeWeekUsesSavedPlanAgainstExistingTdeeWithoutAddingWorkoutCalories() {
        val result = WeeklyBodyExpectation.calculate(
            maintenanceKcal = 2_500,
            weekStartEpochDay = monday,
            plannedDays = (0L..6L).map { monday + it to 2_000 },
        )
        assertTrue(result.available)
        assertTrue(result.isFullWeek)
        assertEquals(7, result.plannedDays)
        assertEquals(3_500, result.theoreticalDeficitKcal)
        assertEquals(3_500.0 / 7_700.0 * .70, result.expectedFatLossKgMin!!, 1e-8)
        assertEquals(3_500.0 / 7_700.0 * 1.20, result.expectedFatLossKgMax!!, 1e-8)
    }

    @Test
    fun partialWeekDoesNotInventMissingDaysOrExtrapolateToFullWeek() {
        val result = WeeklyBodyExpectation.calculate(
            maintenanceKcal = 2_500,
            weekStartEpochDay = monday,
            plannedDays = listOf(monday + 4 to 2_000, monday + 5 to 2_000, monday + 6 to 2_000),
        )
        assertEquals(3, result.plannedDays)
        assertFalse(result.isFullWeek)
        assertEquals(1_500, result.theoreticalDeficitKcal)
    }

    @Test
    fun surplusOffsetsDeficitInsteadOfSummingOnlyPositiveDays() {
        val result = WeeklyBodyExpectation.calculate(
            maintenanceKcal = 2_500,
            weekStartEpochDay = monday,
            plannedDays = listOf(monday to 2_000, monday + 1 to 3_000),
        )
        assertFalse(result.available)
        assertEquals(0, result.theoreticalDeficitKcal)
        assertEquals(2, result.plannedDays)
    }

    @Test
    fun missingNonPositiveOrOutOfWeekDaysAreNotTreatedAsPlanned() {
        val result = WeeklyBodyExpectation.calculate(
            maintenanceKcal = 2_500,
            weekStartEpochDay = monday,
            plannedDays = listOf(monday to null, monday + 1 to 0, monday + 2 to 2_000, monday + 2 to 2_100, monday + 7 to 1_000),
        )
        assertTrue(result.available)
        assertEquals(1, result.plannedDays)
        assertEquals(500, result.theoreticalDeficitKcal)
    }

    @Test
    fun incompleteTdeeOrPlanCannotProduceForecast() {
        assertFalse(WeeklyBodyExpectation.calculate(null, monday, listOf(monday to 2_000)).available)
        assertFalse(WeeklyBodyExpectation.calculate(2_500, monday, emptyList()).available)
        assertFalse(WeeklyBodyExpectation.calculate(0, monday, listOf(monday to 2_000)).available)
    }
}
