package com.myfitai.app.domain.review

import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyConsumptionMetricsTest {
    @Test
    fun noRecords_hasNoCoverageOrAdherence() {
        val result = WeeklyConsumptionMetrics.calculate(plannedMealCount = 4, plannedItemCount = 4, records = emptyList())

        assertNull(result.trackingCoveragePercent)
        assertNull(result.adherencePercent)
        assertNull(result.consumedKcal)
        assertEquals(0, result.recordedItemCount)
    }

    @Test
    fun partialRecords_calculatesCoverageAndMealAdherenceSeparately() {
        val result = WeeklyConsumptionMetrics.calculate(
            plannedMealCount = 4,
            plannedItemCount = 5,
            records = listOf(record("MEAL:1", "MEAL", "CONSUMED", 700)),
        )

        assertEquals(20, result.trackingCoveragePercent)
        assertEquals(25, result.adherencePercent)
        assertEquals(700, result.consumedKcal)
    }

    @Test
    fun completeRecords_countsConsumedAndSkippedWithoutTreatingSkippedAsConsumed() {
        val result = WeeklyConsumptionMetrics.calculate(
            plannedMealCount = 4,
            plannedItemCount = 4,
            records = listOf(
                record("MEAL:1", "MEAL", "CONSUMED", 700),
                record("MEAL:2", "MEAL", "CONSUMED", 800),
                record("MEAL:3", "MEAL", "CONSUMED", 600),
                record("MEAL:4", "MEAL", "SKIPPED", 900),
            ),
        )

        assertEquals(100, result.trackingCoveragePercent)
        assertEquals(75, result.adherencePercent)
        assertEquals(3, result.consumedMealCount)
        assertEquals(1, result.skippedMealCount)
        assertEquals(2100, result.consumedKcal)
    }

    private fun record(key: String, type: String, status: String, kcal: Int) = FoodConsumptionEntity(
        profileId = 1L,
        planId = 10L,
        planVersionId = 20L,
        dayId = 30L,
        plannedDateEpochDay = 23000L,
        itemType = type,
        itemKey = key,
        mealId = key.substringAfter(':').toLongOrNull(),
        supplementKey = null,
        status = status,
        recordedAtEpochMillis = 1000L,
        updatedAtEpochMillis = 1000L,
        quantityFactor = 1f,
        kcal = kcal,
        proteinG = null,
        carbsG = null,
        fatG = null,
    )
}
