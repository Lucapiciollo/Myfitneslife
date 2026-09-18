package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionIntegrityValidatorTest {
    @Test
    fun rejectsMealWhenMacrosDoNotExplainCalories() {
        val meal = NutritionPlanContract.GeneratedMeal("DINNER", "Casein", 20, 355, 19f, 112f, 6f, "", listOf(
            NutritionPlanContract.GeneratedIngredient("casein", 30f, "g", "30 g", "RAW", "HIGH", "PROTEIN")
        ))
        val response = NutritionPlanContract.Response(10, listOf(NutritionPlanContract.GeneratedDay(10, 355, 19f, 112f, 6f, listOf(meal))), NutritionPlanContract.AgentValidation(true, "ok"))
        val result = NutritionIntegrityValidator.validate(response, NutritionBusinessValidator.Targets(355.0, 19.0, 112.0, 6.0), 1)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "MACRO_CALORIE_INCONSISTENCY" })
    }

    @Test
    fun acceptsConsistentMealAndSerializesAuthoritativeResult() {
        val meal = NutritionPlanContract.GeneratedMeal("LUNCH", "Riso e pollo", 20, 500, 40f, 50f, 10f, "", listOf(
            NutritionPlanContract.GeneratedIngredient("riso", 100f, "g", "100 g", "DRY", "HIGH", "CARBOHYDRATE")
        ))
        val response = NutritionPlanContract.Response(10, listOf(NutritionPlanContract.GeneratedDay(10, 500, 40f, 50f, 10f, listOf(meal))), NutritionPlanContract.AgentValidation(true, "ok"))
        val result = NutritionIntegrityValidator.validate(response, NutritionBusinessValidator.Targets(500.0, 40.0, 50.0, 10.0), 1)
        assertTrue(result.valid)
        assertTrue(result.toJson().getBoolean("valid"))
    }
}
