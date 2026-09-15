package com.myfitai.app.domain.calculation

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveNutritionTargetEngineTest {

    private fun evidence(delta: Double?, days: Int = 28, count: Int = 3) =
        AdaptiveNutritionTargetEngine.Evidence(count = count, spanDays = days, delta = delta)

    private fun input(
        goal: LocalCalculationEngine.Goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
        weightDelta: Double? = 0.0,
        bodyFatDelta: Double? = 0.0,
        muscleDelta: Double? = 0.0,
        waistDelta: Double? = 0.0,
        abdomenDelta: Double? = 0.0,
        days: Int = 28,
    ) = AdaptiveNutritionTargetEngine.Input(
        goal = goal,
        tdeeKcal = 2800.0,
        baseTargetKcal = 2380.0,
        currentWeightKg = 90.0,
        weight = evidence(weightDelta, days),
        bodyFat = evidence(bodyFatDelta, days),
        muscleMass = evidence(muscleDelta, days),
        waist = evidence(waistDelta, days),
        abdomen = evidence(abdomenDelta, days),
    )

    @Test
    fun insufficientHistoryKeepsBaseTarget() {
        val result = AdaptiveNutritionTargetEngine.adjust(input(days = 14))
        assertEquals(AdaptiveNutritionTargetEngine.Decision.INSUFFICIENT_DATA, result.decision)
        assertEquals(2380.0, result.targetKcal!!, 0.01)
    }

    @Test
    fun favorableWaistTrendKeepsDeficit() {
        val result = AdaptiveNutritionTargetEngine.adjust(input(waistDelta = -2.0))
        assertEquals(AdaptiveNutritionTargetEngine.Decision.KEEP, result.decision)
        assertEquals(2380.0, result.targetKcal!!, 0.01)
    }

    @Test
    fun sustainedStallIncreasesDeficitBySmallStep() {
        val result = AdaptiveNutritionTargetEngine.adjust(input())
        assertEquals(AdaptiveNutritionTargetEngine.Decision.INCREASE_DEFICIT, result.decision)
        assertEquals(2310.0, result.targetKcal!!, 0.01)
    }

    @Test
    fun muscleDeclineReducesDeficit() {
        val result = AdaptiveNutritionTargetEngine.adjust(input(muscleDelta = -0.8, waistDelta = -1.5))
        assertEquals(AdaptiveNutritionTargetEngine.Decision.REDUCE_DEFICIT, result.decision)
        assertEquals(2450.0, result.targetKcal!!, 0.01)
    }

    @Test
    fun maintenanceIsNotAdapted() {
        val result = AdaptiveNutritionTargetEngine.adjust(
            input(goal = LocalCalculationEngine.Goal.MAINTENANCE, waistDelta = 0.0)
                .copy(baseTargetKcal = 2800.0)
        )
        assertEquals(AdaptiveNutritionTargetEngine.Decision.NOT_APPLICABLE, result.decision)
        assertEquals(2800.0, result.targetKcal!!, 0.01)
    }
}
