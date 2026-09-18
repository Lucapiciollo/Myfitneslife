package com.myfitai.app.domain.calculation

import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyTargetPresentationTest {
    @Test
    fun deficit_isShownWithMaintenanceAndDifference() {
        val state = EnergyTargetPresentation.build(result(tdee = 2850.0, target = 2400.0))

        assertEquals(EnergyTargetPresentation.Mode.DEFICIT, state.mode)
        assertEquals(2850, state.tdeeKcal)
        assertEquals(2400, state.normalTargetKcal)
        assertEquals(-450, state.differenceKcal)
        assertEquals(2400, state.effectiveTargetKcal)
    }

    @Test
    fun maintenance_isShownWhenDifferenceIsWithinClassificationBand() {
        val state = EnergyTargetPresentation.build(result(tdee = 2850.0, target = 2880.0))

        assertEquals(EnergyTargetPresentation.Mode.MAINTENANCE, state.mode)
        assertEquals(30, state.differenceKcal)
    }

    @Test
    fun surplus_isShownWithPositiveDifference() {
        val state = EnergyTargetPresentation.build(result(tdee = 2850.0, target = 3100.0))

        assertEquals(EnergyTargetPresentation.Mode.SURPLUS, state.mode)
        assertEquals(250, state.differenceKcal)
    }

    @Test
    fun missingCalculation_isExplicit() {
        val state = EnergyTargetPresentation.build(null)

        assertEquals(EnergyTargetPresentation.Mode.INSUFFICIENT_DATA, state.mode)
        assertEquals(null, state.effectiveTargetKcal)
    }

    @Test
    fun recoveryOverridesBaseModeAndEffectiveTarget() {
        val recovery = com.myfitai.app.domain.food.NutritionRecoveryTargetEngine.State(
            budgetBeforeKcal = 700,
            plannedRecoveryKcal = 175,
            effectiveTargetKcal = 2675,
            budgetAfterPlannedKcal = 525,
            confirmedRecoveryKcal = 0,
            budgetAfterConfirmedKcal = 700,
            remainingDays = 4,
        )
        val state = EnergyTargetPresentation.build(result(tdee = 2850.0, target = 2850.0), recovery)

        assertEquals(EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED, state.mode)
        assertEquals(2675, state.effectiveTargetKcal)
        assertEquals(525, state.recovery?.budgetAfterPlannedKcal)
    }

    private fun result(tdee: Double?, target: Double?): LocalCalculationEngine.Result = LocalCalculationEngine.Result(
        bmi = null,
        bmrKcal = null,
        tdeeKcal = tdee,
        targetKcal = target,
        proteinG = null,
        fatG = null,
        carbsG = null,
        waistHeightRatio = null,
        bmrMethod = null,
    )
}
