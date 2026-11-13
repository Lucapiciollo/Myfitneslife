package com.myfitai.app.domain.calculation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCalculationEngineTest {

    @Test
    fun tdeeIsBmrTimesTheFactorOfTheProfileActivityLevel() {
        val factors = mapOf(
            LocalCalculationEngine.ActivityLevel.SEDENTARY to 1.20,
            LocalCalculationEngine.ActivityLevel.LIGHT to 1.375,
            LocalCalculationEngine.ActivityLevel.MODERATE to 1.55,
            LocalCalculationEngine.ActivityLevel.VERY_ACTIVE to 1.725,
            LocalCalculationEngine.ActivityLevel.EXTREME to 1.90,
        )
        factors.forEach { (level, factor) ->
            val result = LocalCalculationEngine.calculate(
                LocalCalculationEngine.Input(
                    weightKg = 80.0, heightCm = 180.0, ageYears = 40,
                    biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
                    activityLevel = level, goal = LocalCalculationEngine.Goal.MAINTENANCE,
                )
            )
            assertEquals("$level", result.bmrKcal!! * factor, result.tdeeKcal!!, 1e-9)
            // Maintenance: the goal factor is 1, so the target is the TDEE itself, one value for every day.
            assertEquals("$level", result.tdeeKcal!!, result.targetKcal!!, 1e-9)
        }
    }

    @Test
    fun storedActivityLevelsStayReadableWhateverTheirHistoricalSpelling() {
        assertEquals(LocalCalculationEngine.ActivityLevel.SEDENTARY, ProfileCalculationMapper.activity("Sedentario"))
        assertEquals(LocalCalculationEngine.ActivityLevel.LIGHT, ProfileCalculationMapper.activity(" leggermente attivo "))
        assertEquals(LocalCalculationEngine.ActivityLevel.MODERATE, ProfileCalculationMapper.activity("Moderatamente attivo"))
        assertEquals(LocalCalculationEngine.ActivityLevel.VERY_ACTIVE, ProfileCalculationMapper.activity("MOLTO ATTIVO"))
        assertEquals(LocalCalculationEngine.ActivityLevel.EXTREME, ProfileCalculationMapper.activity("Estremamente attivo"))
        assertNull(ProfileCalculationMapper.activity(null))
        assertNull(ProfileCalculationMapper.activity("allenamento"))
    }

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
        // K6: grassi = max(0,6*80 = 48; 0,20*2580,4/9 = 57,34) = 57,34 (prima 0,8 g/kg = 64).
        assertEquals(57.34, result.fatG!!, 0.01)
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
            LocalCalculationEngine.classifyRecomposition(-1.5, 1.5),
        )
        assertEquals(
            LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA,
            LocalCalculationEngine.classifyRecomposition(-1.5, null),
        )
    }

    @Test
    fun recompositionClassification_usesBiaNoiseThresholdsNotTheOldPointThree() {
        // Fat -0.5 points and lean +0.5 kg are below the BIA noise (1.0): no reliable change.
        assertEquals(
            LocalCalculationEngine.RecompositionState.STABLE,
            LocalCalculationEngine.classifyRecomposition(-0.5, 0.5),
        )
        // Exactly on the threshold is still noise.
        assertEquals(
            LocalCalculationEngine.RecompositionState.STABLE,
            LocalCalculationEngine.classifyRecomposition(-1.0, 1.0),
        )
        // Fat beyond noise, lean mass within noise.
        assertEquals(
            LocalCalculationEngine.RecompositionState.FAT_LOSS_WITH_STABLE_MUSCLE,
            LocalCalculationEngine.classifyRecomposition(-1.2, 0.8),
        )
        assertEquals(
            LocalCalculationEngine.RecompositionState.MUSCLE_GAIN_WITH_STABLE_FAT,
            LocalCalculationEngine.classifyRecomposition(0.4, 1.4),
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
    fun nutritionValidator_acceptsBoundaryAndRejectsOutsideThreePercent() {
        val targets = NutritionBusinessValidator.Targets(
            kcal = 2000.0,
            proteinG = 160.0,
            carbsG = 220.0,
            fatG = 60.0,
        )

        val atBoundary = NutritionBusinessValidator.validate(
            targets,
            NutritionBusinessValidator.Actuals(
                kcal = 2060.0,
                proteinG = 155.2,
                carbsG = 226.6,
                fatG = 58.2,
            )
        )
        assertTrue(atBoundary.valid)

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
