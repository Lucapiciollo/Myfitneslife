package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatAdjustmentContractTest {
    private val targets = NutritionBusinessValidator.Targets(2000.0, 150.0, 200.0, 60.0)

    @Test
    fun validAdjustment_changesExactlyFutureMealsAndBalancesActualDay() {
        val locked = listOf(meal(0, 480, 1000, 75f, 100f, 30f))
        val future = listOf(
            meal(1, 900, 500, 35f, 50f, 15f),
            meal(2, 1200, 500, 35f, 50f, 15f),
        )
        val response = CheatAdjustmentContract.Response(
            estimate = CheatAdjustmentContract.Estimate(300, 5f, 40f, 10f, "medium", "stima prudente"),
            adaptationPossible = true,
            adaptationReason = "Bilanciamento dei soli pasti futuri",
            replacementMeals = listOf(
                replacement(1, 900, 350, 35f, 30f, 10f),
                replacement(2, 1200, 350, 35f, 30f, 10f),
            ),
            agentValidation = NutritionPlanContract.AgentValidation(true, "advisory"),
        )
        assertTrue(CheatAdjustmentContract.validateBusiness(response, locked, future, targets).isSuccess)
    }

    @Test
    fun changingMealTimeIsRejected() {
        val locked = listOf(meal(0, 480, 1000, 75f, 100f, 30f))
        val future = listOf(meal(1, 900, 700, 70f, 60f, 20f))
        val response = CheatAdjustmentContract.Response(
            estimate = CheatAdjustmentContract.Estimate(300, 5f, 40f, 10f, "medium", ""),
            adaptationPossible = true,
            adaptationReason = "",
            replacementMeals = listOf(replacement(1, 930, 700, 70f, 60f, 20f)),
            agentValidation = NutritionPlanContract.AgentValidation(true, ""),
        )
        assertTrue(CheatAdjustmentContract.validateBusiness(response, locked, future, targets).isFailure)
    }

    @Test
    fun impossibleBalanceAcceptsNoAdaptationInsteadOfPunitiveRestriction() {
        val locked = listOf(meal(0, 480, 1200, 80f, 100f, 35f))
        val future = listOf(meal(1, 900, 400, 30f, 50f, 10f), meal(2, 1200, 400, 30f, 50f, 10f))
        val response = CheatAdjustmentContract.Response(
            estimate = CheatAdjustmentContract.Estimate(1000, 20f, 100f, 50f, "medium", ""),
            adaptationPossible = false,
            adaptationReason = "Non bilanciabile senza restrizione eccessiva",
            replacementMeals = emptyList(),
            agentValidation = NutritionPlanContract.AgentValidation(true, ""),
        )
        assertTrue(CheatAdjustmentContract.validateBusiness(response, locked, future, targets).isSuccess)
    }

    private fun meal(order: Int, time: Int, kcal: Int, p: Float, c: Float, f: Float) = FoodMeal(
        id = order.toLong() + 1,
        dayId = 1,
        sortOrder = order,
        type = "Pasto",
        title = "Originale $order",
        timeMinutes = time,
        kcal = kcal,
        proteinG = p,
        carbsG = c,
        fatG = f,
        preparation = "Preparazione",
        ingredients = emptyList(),
    )

    private fun replacement(order: Int, time: Int, kcal: Int, p: Float, c: Float, f: Float) =
        CheatAdjustmentContract.ReplacementMeal(
            sortOrder = order,
            type = "Pasto",
            title = "Adattato $order",
            timeMinutes = time,
            kcal = kcal,
            proteinG = p,
            carbsG = c,
            fatG = f,
            preparation = "Preparazione",
            ingredients = listOf(NutritionPlanContract.GeneratedIngredient("Riso", 100f, "g", "100 g", "cooked", "medium", "cereali")),
        )
}
