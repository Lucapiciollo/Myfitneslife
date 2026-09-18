package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MealAlternativeContractTest {
    private val source = FoodMeal(1L, 1L, 0, "Pranzo", "Pasto originale", 720, 700, 45f, 80f, 20f, "", emptyList())

    @Test
    fun acceptsAlternativeBelowOriginalCalories() {
        val response = MealAlternativeContract.Response(
            alternatives = (1..5).map { alternative(it, 650) },
            agentValidation = NutritionPlanContract.AgentValidation(true, ""),
        )
        assertTrue(MealAlternativeContract.validateBusiness(response, source).isSuccess)
    }

    @Test
    fun rejectsAlternativeAboveOriginalCalories() {
        val response = MealAlternativeContract.Response(
            alternatives = (1..5).map { alternative(it, 701) },
            agentValidation = NutritionPlanContract.AgentValidation(true, ""),
        )
        assertFalse(MealAlternativeContract.validateBusiness(response, source).isSuccess)
    }

    private fun alternative(index: Int, kcal: Int) = MealAlternativeContract.Alternative(
        title = "Alternativa $index",
        kcal = kcal,
        proteinG = 40f,
        carbsG = 70f,
        fatG = 18f,
        preparation = "Preparazione",
        reason = "Stessa categoria e orario",
        ingredients = listOf(MealAlternativeContract.Ingredient("Riso", 80f, "g", "80 g", "COOKED", "STANDARD", "cereali")),
    )
}
