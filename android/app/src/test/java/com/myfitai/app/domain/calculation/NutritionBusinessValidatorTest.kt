package com.myfitai.app.domain.calculation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionBusinessValidatorTest {
    private val targets = NutritionBusinessValidator.Targets(kcal = 2000.0, proteinG = 150.0, carbsG = 200.0, fatG = 60.0)

    private fun actuals(factor: Double) = NutritionBusinessValidator.Actuals(
        kcal = targets.kcal * factor,
        proteinG = targets.proteinG * factor,
        carbsG = targets.carbsG * factor,
        fatG = targets.fatG * factor,
    )

    @Test
    fun symmetric_allowsAboveAndBelowWithinTolerance() {
        assertTrue(NutritionBusinessValidator.validate(targets, actuals(1.03)).valid)
        assertTrue(NutritionBusinessValidator.validate(targets, actuals(0.97)).valid)
    }

    @Test
    fun belowOnly_rejectsAnyValueAboveTarget() {
        assertFalse(NutritionBusinessValidator.validate(targets, actuals(1.01), belowOnly = true).valid)
        assertFalse(NutritionBusinessValidator.validate(targets, actuals(1.03), belowOnly = true).valid)
    }

    @Test
    fun belowOnly_acceptsAtTargetAndWithinThreePercentBelow() {
        assertTrue(NutritionBusinessValidator.validate(targets, actuals(1.0), belowOnly = true).valid)
        assertTrue(NutritionBusinessValidator.validate(targets, actuals(0.97), belowOnly = true).valid)
    }

    @Test
    fun belowOnly_rejectsMoreThanToleranceBelow() {
        assertFalse(NutritionBusinessValidator.validate(targets, actuals(0.96), belowOnly = true, tolerance = 0.03).valid)
        assertTrue(NutritionBusinessValidator.validate(targets, actuals(0.96), belowOnly = true, tolerance = 0.04).valid)
    }
}
