package com.myfitai.app.domain.calculation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCalculationEngineTest {

    @Test
    fun calculate_usesKatchMcArdleWhenBodyFatIsAvailable() {
        val result = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 80.0,
                heightCm = 180.0,
                ageYears = 40,
                bodyFatPercent = 20.0,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = LocalCalculationEngine.Goal.RECOMPOSITION,
                waistCm = 88.0,
            )
        )

        assertEquals("KATCH_MCARDLE", result.bmrMethod)
        assertEquals(24.69, result.bmi!!, 0.02)
        assertEquals(1752.4, result.bmrKcal!!, 0.2)
        assertEquals(2716.2, result.tdeeKcal!!, 0.3)
        assertEquals(2580.4, result.targetKcal!!, 0.4)
        assertEquals(160.0, result.proteinG!!, 0.01)
        assertEquals(64.0, result.fatG!!, 0.01)
        assertEquals(0.4889, result.waistHeightRatio!!, 0.001)
    }

    @Test
    fun calculate_doesNotInventBmrWithoutBodyFatOrSex() {
        val result = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 80.0,
                heightCm = 180.0,
                ageYears = 40,
                bodyFatPercent = null,
                biologicalSex = null,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = LocalCalculationEngine.Goal.MAINTENANCE,
            )
        )

        assertNull(result.bmrKcal)
        assertNull(result.tdeeKcal)
        assertNull(result.targetKcal)
        assertNull(result.proteinG)
    }

    @Test
    fun calculate_usesMifflinWhenRequiredInputsArePresent() {
        val result = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 80.0,
                heightCm = 180.0,
                ageYears = 40,
                biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
                activityLevel = LocalCalculationEngine.ActivityLevel.SEDENTARY,
                goal = LocalCalculationEngine.Goal.MAINTENANCE,
            )
        )

        assertEquals("MIFFLIN_ST_JEOR", result.bmrMethod)
        assertEquals(1730.0, result.bmrKcal!!, 0.01)
        assertEquals(2076.0, result.tdeeKcal!!, 0.01)
    }

    @Test
    fun calculate_addsExerciseCaloriesToBaseTdeeWithoutChangingBmr() {
        val result = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 80.0,
                heightCm = 180.0,
                ageYears = 40,
                biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
                activityLevel = LocalCalculationEngine.ActivityLevel.SEDENTARY,
                goal = LocalCalculationEngine.Goal.MAINTENANCE,
                exerciseKcal = 500,
            ),
        )

        assertEquals(2076.0, result.baseTdeeKcal!!, 0.01)
        assertEquals(2576.0, result.tdeeKcal!!, 0.01)
        assertEquals(500, result.exerciseKcal)
    }

    @Test
    fun dailyCalorieProgress_capsBatteryAtTargetAndReportsExcess() {
        val empty = DailyCalorieProgress.calculate(2400, 0)
        val full = DailyCalorieProgress.calculate(2400, 2400)
        val excess = DailyCalorieProgress.calculate(2400, 2600)

        assertEquals(0, empty.percent)
        assertEquals(DailyCalorieProgress.Status.EMPTY, empty.status)
        assertEquals(100, full.percent)
        assertEquals(DailyCalorieProgress.Status.COMPLETE, full.status)
        assertEquals(100, excess.percent)
        assertEquals(200, excess.exceededKcal)
        assertEquals(DailyCalorieProgress.Status.EXCEEDED, excess.status)
    }

    @Test
    fun recoveryRemainingPercent_isZeroWithoutBudgetAndFallsAsBudgetIsRecovered() {
        val empty = DailyCalorieProgress.recoveryRemainingPercent(null)
        val state = com.myfitai.app.domain.food.NutritionRecoveryTargetEngine.State(
            budgetBeforeKcal = 500,
            plannedRecoveryKcal = 100,
            effectiveTargetKcal = 2300,
            budgetAfterPlannedKcal = 400,
            confirmedRecoveryKcal = 0,
            budgetAfterConfirmedKcal = 500,
            remainingDays = 5,
        )

        assertEquals(0, empty)
        assertEquals(66, DailyCalorieProgress.recoveryRemainingPercent(state))
    }

    @Test
    fun trend_sortsValuesBeforeCalculatingDelta() {
        val stats = LocalCalculationEngine.trend(
            listOf(
                LocalCalculationEngine.TimedValue(3000, 84.0),
                LocalCalculationEngine.TimedValue(1000, 90.0),
                LocalCalculationEngine.TimedValue(2000, 87.0),
            )
        )

        assertEquals(3, stats.count)
        assertEquals(90.0, stats.first!!, 0.001)
        assertEquals(84.0, stats.latest!!, 0.001)
        assertEquals(-6.0, stats.delta!!, 0.001)
        assertEquals(LocalCalculationEngine.TrendDirection.DOWN, stats.direction)
    }

    @Test
    fun recompositionClassification_isDescriptiveAndRequiresBothSignals() {
        assertEquals(
            LocalCalculationEngine.RecompositionState.FAVORABLE,
            LocalCalculationEngine.classifyRecomposition(-1.2, 0.8),
        )
        assertEquals(
            LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA,
            LocalCalculationEngine.classifyRecomposition(-1.2, null),
        )
    }

    @Test
    fun profileMapper_matchesStoredItalianValues() {
        assertEquals(
            LocalCalculationEngine.ActivityLevel.MODERATE,
            ProfileCalculationMapper.activity("Moderatamente attivo"),
        )
        assertEquals(
            LocalCalculationEngine.Goal.RECOMPOSITION,
            ProfileCalculationMapper.goal("Ricomposizione"),
        )
    }

    @Test
    fun nutritionValidator_acceptsTargetAndLowerThreePercentBoundary_butRejectsAboveTarget() {
        val targets = NutritionBusinessValidator.Targets(
            kcal = 2000.0,
            proteinG = 160.0,
            carbsG = 220.0,
            fatG = 60.0,
        )

        val atTarget = NutritionBusinessValidator.validate(
            targets,
            NutritionBusinessValidator.Actuals(
                kcal = 2000.0,
                proteinG = 155.2,
                carbsG = 220.0,
                fatG = 58.2,
            )
        )
        assertTrue(atTarget.valid)

        val atLowerBoundary = NutritionBusinessValidator.validate(
            targets,
            NutritionBusinessValidator.Actuals(
                kcal = 1940.0,
                proteinG = 155.2,
                carbsG = 213.4,
                fatG = 58.2,
            )
        )
        assertTrue(atLowerBoundary.valid)

        val outside = NutritionBusinessValidator.validate(
            targets,
            NutritionBusinessValidator.Actuals(
                kcal = 2061.0,
                proteinG = 160.0,
                carbsG = 220.0,
                fatG = 60.0,
            )
        )
        assertFalse(outside.valid)
        assertFalse(outside.kcal.valid)
    }
}
