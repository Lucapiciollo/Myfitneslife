package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
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

    private fun ingredient(name: String) = NutritionPlanContract.GeneratedIngredient(name, 100f, "g", "100 g", "RAW", "HIGH", "OTHER")

    private fun plan(
        title: String,
        ingredients: List<String>,
        supplements: List<NutritionPlanContract.GeneratedSupplement> = emptyList(),
    ) = NutritionPlanContract.Response(
        10,
        listOf(
            NutritionPlanContract.GeneratedDay(
                10, 500, 30f, 50f, 15f,
                listOf(NutritionPlanContract.GeneratedMeal("LUNCH", title, 750, 500, 30f, 50f, 15f, "", ingredients.map(::ingredient))),
                supplements,
            ),
        ),
        NutritionPlanContract.AgentValidation(true, "ok"),
    )

    @Test
    fun planWithLactoseIntolerance_rejectsMozzarellaAndAcceptsLactoseFreeVariant() {
        val profile = DietaryProfile(intolerances = listOf("lattosio"))
        val rejected = FoodConstraintValidator.validate(plan("Pranzo", listOf("Riso", "Mozzarella di bufala")), profile)
        assertTrue(rejected.exceptionOrNull()?.message.orEmpty().startsWith("FOOD_CONSTRAINT_VIOLATION:INTOLERANCE:lattosio:Mozzarella"))
        assertTrue(FoodConstraintValidator.validate(plan("Pranzo", listOf("Riso", "Yogurt senza lattosio")), profile).isSuccess)
    }

    @Test
    fun planMealTitleIsCheckedEvenWhenIngredientsLookClean() {
        val profile = DietaryProfile(intolerances = listOf("glutine"))
        assertTrue(FoodConstraintValidator.validate(plan("Pasta al pomodoro", listOf("Riso", "Pomodori")), profile).isFailure)
        assertTrue(FoodConstraintValidator.validate(plan("Pasto post allenamento", listOf("Riso", "Pomodori")), profile).isSuccess)
    }

    @Test
    fun planSupplementNameAndNoteAreChecked() {
        val profile = DietaryProfile(intolerances = listOf("lattosio"))
        fun supplement(name: String, notes: String) =
            NutritionPlanContract.GeneratedSupplement("PROTEIN_POWDER", name, 30f, "g", 1000, 120, 24f, 2f, 1f, notes)
        assertTrue(FoodConstraintValidator.validate(plan("Pranzo", listOf("Riso"), listOf(supplement("Proteine di soia", "con mozzarella"))), profile).isFailure)
        assertTrue(FoodConstraintValidator.validate(plan("Pranzo", listOf("Riso"), listOf(supplement("Proteine di soia", "in acqua"))), profile).isSuccess)
    }

    @Test
    fun planWithVeganStyleRejectsHiddenAnimalProducts() {
        val profile = DietaryProfile(dietStyle = "Vegano")
        listOf("Burro", "Miele", "Parmigiano", "Panna", "Uova").forEach { food ->
            assertTrue(food, FoodConstraintValidator.validate(plan("Pranzo", listOf("Riso", food)), profile).isFailure)
        }
        assertTrue(FoodConstraintValidator.validate(plan("Pranzo", listOf("Riso", "Latte di avena", "Burro di arachidi")), profile).isSuccess)
    }

    @Test
    fun unreadablePreferences_blockPlanAdviceAndAlternativesWithStableCode() {
        val profile = DietaryProfile.parse("{corrotto")
        assertEquals("PROFILE_CONSTRAINTS_UNREADABLE", FoodConstraintValidator.validate(plan("Pranzo", listOf("Riso")), profile).exceptionOrNull()?.message)
        val advice = NutritionAdviceContract.Response(
            true, "ok", (1..5).map { NutritionAdviceContract.Suggestion("Opzione $it", "ok", 300, 20f, 30f, 5f, listOf("riso")) }, "",
            NutritionPlanContract.AgentValidation(true, ""),
        )
        assertTrue(NutritionAdviceContract.validateBusiness(advice, profile).isFailure)
    }
}