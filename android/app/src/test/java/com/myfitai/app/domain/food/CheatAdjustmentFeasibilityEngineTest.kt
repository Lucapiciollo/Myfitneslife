package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatAdjustmentFeasibilityEngineTest {
    private val targets = NutritionBusinessValidator.Targets(2858.0, 178.0, 376.4667, 71.2)
    private val locked = listOf(
        meal(0, 570, 37f, 75f, 12f),
        meal(1, 345, 25f, 42f, 8f),
        meal(2, 760, 52f, 88f, 22f),
    )
    private val future = listOf(meal(3, 442, 29f, 59f, 8f), meal(4, 742, 35f, 112f, 21f))

    @Test
    fun pizzaCase_isImpossibleBeforeAiBecauseFatIsAlreadyAboveMaximum() {
        val result = evaluate(CheatAdjustmentContract.Estimate(1000, 40f, 120f, 38f, "medium", "confirmed"))

        assertFalse(result.possible)
        assertEquals(CheatAdjustmentFeasibilityEngine.ReasonCode.UNAVOIDABLE_FAT_ABOVE_MAX, result.reasonCode)
        assertEquals(80.0, result.unavoidable.fatG, 0.001)
        assertEquals(71.2, result.maximum.fatG, 0.001)
    }

    @Test
    fun unavoidableKcalAboveMaximum_isImpossible() {
        val result = evaluate(CheatAdjustmentContract.Estimate(2_000, 1f, 1f, 1f, "medium", ""))

        assertEquals(CheatAdjustmentFeasibilityEngine.ReasonCode.UNAVOIDABLE_KCAL_ABOVE_MAX, result.reasonCode)
    }

    @Test
    fun unavoidableProteinAboveMaximum_isImpossible() {
        val result = evaluate(CheatAdjustmentContract.Estimate(1, 100f, 1f, 1f, "medium", ""))

        assertEquals(CheatAdjustmentFeasibilityEngine.ReasonCode.UNAVOIDABLE_PROTEIN_ABOVE_MAX, result.reasonCode)
    }

    @Test
    fun unavoidableCarbsAboveMaximum_isImpossible() {
        val result = evaluate(CheatAdjustmentContract.Estimate(1, 1f, 200f, 1f, "medium", ""))

        assertEquals(CheatAdjustmentFeasibilityEngine.ReasonCode.UNAVOIDABLE_CARBS_ABOVE_MAX, result.reasonCode)
    }

    @Test
    fun totalsBelowUpperBounds_arePossible() {
        val result = evaluate(CheatAdjustmentContract.Estimate(100, 1f, 5f, 1f, "medium", ""))

        assertTrue(result.possible)
        assertEquals(CheatAdjustmentFeasibilityEngine.ReasonCode.POSSIBLE, result.reasonCode)
    }

    @Test
    fun confirmedDeviationIsUsedExactly() {
        val estimate = CheatAdjustmentContract.Estimate(321, 12.5f, 22.25f, 9.75f, "medium", "do not recalculate")
        val result = evaluate(estimate)

        assertEquals(1_675.0 + 321.0, result.unavoidable.kcal, 0.001)
        assertEquals(114.0 + 12.5, result.unavoidable.proteinG, 0.001)
        assertEquals(205.0 + 22.25, result.unavoidable.carbsG, 0.001)
        assertEquals(42.0 + 9.75, result.unavoidable.fatG, 0.001)
    }

    @Test
    fun minimumFutureCaloriesCanMakeAdaptationImpossible() {
        val result = CheatAdjustmentFeasibilityEngine.evaluate(
            targets = NutritionBusinessValidator.Targets(2_000.0, 200.0, 300.0, 100.0),
            lockedMeals = listOf(meal(0, 1_860, 50f, 100f, 50f)),
            deviation = CheatAdjustmentContract.Estimate(1, 1f, 1f, 1f, "medium", ""),
            futureMeals = listOf(meal(1, 10, 1f, 1f, 1f), meal(2, 10, 1f, 1f, 1f)),
        )

        assertEquals(CheatAdjustmentFeasibilityEngine.ReasonCode.MINIMUM_FUTURE_KCAL_ABOVE_MAX, result.reasonCode)
    }

    private fun evaluate(estimate: CheatAdjustmentContract.Estimate) = CheatAdjustmentFeasibilityEngine.evaluate(
        targets = targets,
        lockedMeals = locked,
        deviation = estimate,
        futureMeals = future,
    )

    private fun meal(order: Int, kcal: Int, protein: Float, carbs: Float, fat: Float) = FoodMeal(
        id = order.toLong() + 1,
        dayId = 1,
        sortOrder = order,
        type = "Pasto",
        title = "Pasto $order",
        timeMinutes = 480 + order * 180,
        kcal = kcal,
        proteinG = protein,
        carbsG = carbs,
        fatG = fat,
        preparation = "Preparazione",
        ingredients = emptyList(),
    )
}
