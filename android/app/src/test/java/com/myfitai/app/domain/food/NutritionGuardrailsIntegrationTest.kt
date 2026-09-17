package com.myfitai.app.domain.food

import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionGuardrailsIntegrationTest {
    @Test
    fun adviceWithDeclaredLactoseIntolerance_rejectsYogurtOptionLocally() {
        val profile = DietaryProfile(intolerances = listOf("lattosio"))
        val suggestions = (1..5).map { index ->
            NutritionAdviceContract.Suggestion(
                title = "Opzione $index",
                reason = "coerente",
                estimatedKcal = 300,
                proteinG = 20f,
                carbsG = 35f,
                fatG = 8f,
                foods = if (index == 1) listOf("yogurt greco", "riso") else listOf("riso", "pollo"),
            )
        }
        val response = NutritionAdviceContract.Response(
            inScope = true,
            answer = "Cinque opzioni",
            suggestions = suggestions,
            assumptions = "",
            agentValidation = NutritionPlanContract.AgentValidation(true, ""),
        )

        assertTrue(NutritionAdviceContract.validateBusiness(response, profile).isFailure)
    }

    @Test
    fun adviceWithHardConstraints_requiresExplicitFoodsForLocalValidation() {
        val profile = DietaryProfile(allergies = listOf("arachidi"))
        val suggestions = (1..5).map { index ->
            NutritionAdviceContract.Suggestion(
                title = "Opzione $index",
                reason = "coerente",
                estimatedKcal = 300,
                proteinG = 20f,
                carbsG = 35f,
                fatG = 8f,
                foods = emptyList(),
            )
        }
        val response = NutritionAdviceContract.Response(
            inScope = true,
            answer = "Cinque opzioni",
            suggestions = suggestions,
            assumptions = "",
            agentValidation = NutritionPlanContract.AgentValidation(true, ""),
        )

        assertTrue(NutritionAdviceContract.validateBusiness(response, profile).isFailure)
    }
}
