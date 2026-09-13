package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodPlanMetricsTest {

    @Test
    fun dayTotals_usesPersistedTotalsWhenAvailable() {
        val day = day(totalKcal = 2200, protein = 160f, carbs = 230f, fat = 70f)
        val totals = FoodPlanMetrics.dayTotals(day)
        assertEquals(2200.0, totals.kcal!!, 0.001)
        assertEquals(160.0, totals.proteinG!!, 0.001)
    }

    @Test
    fun weeklyAverage_ignoresMissingDaysInsteadOfTreatingThemAsZero() {
        val days = listOf(
            day(totalKcal = 2000, protein = 150f, carbs = 220f, fat = 65f),
            day(totalKcal = 2400, protein = 170f, carbs = 260f, fat = 75f),
            day(totalKcal = null, protein = null, carbs = null, fat = null),
        )
        val average = FoodPlanMetrics.weeklyAverage(days)
        assertEquals(2200.0, average.kcal!!, 0.001)
        assertEquals(160.0, average.proteinG!!, 0.001)
        assertEquals(240.0, average.carbsG!!, 0.001)
        assertEquals(70.0, average.fatG!!, 0.001)
    }

    @Test
    fun weeklyAverage_returnsNullWhenMetricIsAbsentEverywhere() {
        val average = FoodPlanMetrics.weeklyAverage(listOf(day(null, null, null, null)))
        assertNull(average.kcal)
        assertNull(average.proteinG)
    }

    private fun day(totalKcal: Int?, protein: Float?, carbs: Float?, fat: Float?) = FoodPlanDay(
        id = 1,
        dateEpochDay = 1,
        totalKcal = totalKcal,
        proteinG = protein,
        carbsG = carbs,
        fatG = fat,
        meals = emptyList(),
    )
}
