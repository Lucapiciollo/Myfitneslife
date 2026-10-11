package com.myfitai.app.domain.food

import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class FoodConsumptionMetricsTest {
    @Test
    fun dayTotals_withoutRecords_hasNoRecordedCoverage() {
        val totals = FoodConsumptionMetrics.dayTotals(emptyList())

        assertEquals(0.0, totals.kcal, 0.001)
        assertEquals(0, totals.consumedCount)
        assertEquals(0, totals.skippedCount)
        assertEquals(0, totals.recordedCount)
    }

    @Test
    fun dayTotals_countsOnlyConsumedRecords() {
        val records = listOf(
            record(status = FoodConsumptionStatus.CONSUMED, kcal = 700, protein = 50f, carbs = 80f, fat = 18f),
            record(status = FoodConsumptionStatus.SKIPPED, kcal = 800, protein = 45f, carbs = 75f, fat = 30f),
        )

        val totals = FoodConsumptionMetrics.dayTotals(records)

        assertEquals(700.0, totals.kcal, 0.001)
        assertEquals(50.0, totals.proteinG, 0.001)
        assertEquals(80.0, totals.carbsG, 0.001)
        assertEquals(18.0, totals.fatG, 0.001)
        assertEquals(1, totals.consumedCount)
        assertEquals(1, totals.skippedCount)
        assertEquals(2, totals.recordedCount)
    }

    @Test
    fun recordsForDate_keepsConsumedMealsFromOlderPlanVersions() {
        val records = listOf(
            record(status = FoodConsumptionStatus.CONSUMED, kcal = 500, protein = 30f, carbs = 60f, fat = 12f)
                .copy(planVersionId = 20L, plannedDateEpochDay = 23_000L),
            record(status = FoodConsumptionStatus.CONSUMED, kcal = 400, protein = 25f, carbs = 45f, fat = 10f)
                .copy(planVersionId = 21L, plannedDateEpochDay = 23_000L),
            record(status = FoodConsumptionStatus.CONSUMED, kcal = 300, protein = 20f, carbs = 30f, fat = 8f)
                .copy(planVersionId = 21L, plannedDateEpochDay = 23_001L),
        )

        val today = FoodConsumptionMetrics.recordsForDate(records, 23_000L)
        val totals = FoodConsumptionMetrics.dayTotals(today)

        assertEquals(listOf(20L, 21L), today.map { it.planVersionId })
        assertEquals(900.0, totals.kcal, 0.001)
        assertEquals(2, totals.consumedCount)
    }

    private fun record(status: FoodConsumptionStatus, kcal: Int, protein: Float, carbs: Float, fat: Float) = FoodConsumptionEntity(
        profileId = 1L,
        planId = 10L,
        planVersionId = 20L,
        dayId = 30L,
        plannedDateEpochDay = 23000L,
        itemType = FoodConsumptionItemType.MEAL.name,
        itemKey = "MEAL:$kcal",
        mealId = kcal.toLong(),
        supplementKey = null,
        status = status.name,
        recordedAtEpochMillis = 1000L,
        updatedAtEpochMillis = 1000L,
        quantityFactor = 1f,
        kcal = kcal,
        proteinG = protein,
        carbsG = carbs,
        fatG = fat,
    )
}
