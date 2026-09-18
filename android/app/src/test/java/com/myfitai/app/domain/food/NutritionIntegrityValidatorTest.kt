package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NutritionIntegrityValidatorTest {
    @Test
    fun rejectsMealWhenMacrosDoNotExplainDeclaredCalories() {
        val meal = NutritionPlanContract.GeneratedMeal("DINNER", "Casein Pudding", 1200, 355, 19f, 112f, 6f, "", listOf(
            NutritionPlanContract.GeneratedIngredient("casein", 30f, "g", "30 g", "RAW", "HIGH", "PROTEIN")
        ))
        val day = NutritionPlanContract.GeneratedDay(20_710, 355, 19f, 112f, 6f, listOf(meal))
        val response = NutritionPlanContract.Response(20_710, listOf(day), NutritionPlanContract.AgentValidation(true, "ok"))
        val result = NutritionIntegrityValidator.validate(response, NutritionBusinessValidator.Targets(355.0, 19.0, 112.0, 6.0), 1)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "MACRO_CALORIE_INCONSISTENCY" })
    }

    @Test
    fun acceptsMealWithinRealisticMacroRoundingTolerance() {
        val meal = NutritionPlanContract.GeneratedMeal("LUNCH", "Riso e pollo", 1200, 500, 40f, 50f, 10f, "", listOf(
            NutritionPlanContract.GeneratedIngredient("riso", 100f, "g", "100 g", "DRY", "HIGH", "CARBOHYDRATE")
        ))
        val day = NutritionPlanContract.GeneratedDay(20_710, 500, 40f, 50f, 10f, listOf(meal))
        val response = NutritionPlanContract.Response(20_710, listOf(day), NutritionPlanContract.AgentValidation(true, "ok"))
        val result = NutritionIntegrityValidator.validate(response, NutritionBusinessValidator.Targets(500.0, 40.0, 50.0, 10.0), 1)
        assertTrue(result.valid)
        assertEquals("nutrition-integrity-v1", result.validatorVersion)
    }
}
