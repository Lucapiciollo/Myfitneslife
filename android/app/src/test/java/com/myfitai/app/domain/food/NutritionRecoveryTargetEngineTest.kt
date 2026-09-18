package com.myfitai.app.domain.food

import com.myfitai.app.data.local.entity.NutritionRecoveryEventEntity
import com.myfitai.app.data.local.entity.NutritionRecoveryWithdrawalEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class NutritionRecoveryTargetEngineTest {
    private val event = NutritionRecoveryEventEntity(
        id = 1,
        profileId = 1,
        createdAtEpochMillis = 1,
        eventEpochDay = 10,
        source = "CHEAT",
        originalExcessKcal = 700,
        remainingKcal = 700,
        recoveredKcal = 0,
        expiresEpochDay = 13,
        status = "ACTIVE",
        reason = "TEST",
    )

    @Test
    fun noBudget_keepsAdaptiveTarget() {
        val state = NutritionRecoveryTargetEngine.calculate(2858, emptyList(), 10)

        assertEquals(0, state.budgetBeforeKcal)
        assertEquals(0, state.plannedRecoveryKcal)
        assertEquals(2858, state.effectiveTargetKcal)
    }

    @Test
    fun sevenHundredOverFourDays_withdrawsIdealAmountAndRespectsTenPercentCap() {
        val state = NutritionRecoveryTargetEngine.calculate(2858, listOf(event), 10)

        assertEquals(700, state.budgetBeforeKcal)
        assertEquals(175, state.plannedRecoveryKcal)
        assertEquals(2683, state.effectiveTargetKcal)
        assertEquals(525, state.budgetAfterPlannedKcal)
    }

    @Test
    fun withdrawalIsIdempotentForRepeatedPlanGeneration() {
        val withdrawal = NutritionRecoveryWithdrawalEntity(1, 10, 1, 175, 0, 1, 1)
        val state = NutritionRecoveryTargetEngine.calculate(2858, listOf(event), 10, withdrawal)

        assertEquals(175, state.plannedRecoveryKcal)
        assertEquals(525, state.budgetAfterPlannedKcal)
    }

    @Test
    fun plannedAndConfirmedRecoveryRemainSeparate() {
        val withdrawal = NutritionRecoveryWithdrawalEntity(1, 10, 1, 175, 0, 1, 1)
        val state = NutritionRecoveryTargetEngine.calculate(2858, listOf(event), 10, withdrawal)

        assertEquals(175, state.plannedRecoveryKcal)
        assertEquals(0, state.confirmedRecoveryKcal)
        assertEquals(700, state.budgetAfterConfirmedKcal)
    }

    @Test
    fun expiredEventDoesNotContribute() {
        val state = NutritionRecoveryTargetEngine.calculate(2858, listOf(event), 14)

        assertEquals(0, state.budgetBeforeKcal)
        assertEquals(0, state.plannedRecoveryKcal)
    }

    @Test
    fun exerciseRaisesFoodAllowanceAndCanReduceRecoveryWithdrawal() {
        val state = NutritionRecoveryTargetEngine.calculate(2858, listOf(event), 10, exerciseKcal = 500)

        assertEquals(500, state.exerciseKcal)
        assertEquals(3358, state.foodAllowanceKcal)
        assertEquals(50, state.plannedRecoveryKcal)
        assertEquals(3308, state.effectiveTargetKcal)
    }

    @Test
    fun exerciseCanAbsorbSmallRecoveryBudgetWithoutWithdrawal() {
        val smallEvent = event.copy(originalExcessKcal = 300, remainingKcal = 300)
        val state = NutritionRecoveryTargetEngine.calculate(2858, listOf(smallEvent), 10, exerciseKcal = 500)

        assertEquals(0, state.budgetBeforeKcal)
        assertEquals(0, state.plannedRecoveryKcal)
        assertEquals(3358, state.effectiveTargetKcal)
    }
}
