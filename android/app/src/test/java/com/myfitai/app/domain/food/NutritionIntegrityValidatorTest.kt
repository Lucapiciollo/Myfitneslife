package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- K6: titoli dei pasti (avviso non bloccante GENERIC_MEAL_TITLE) ---------------------------------

    private fun meal(type: String, title: String, vararg ingredients: String) = NutritionPlanContract.GeneratedMeal(
        type, title, 480, 400, 25f, 40f, 12f, "",
        ingredients.map { NutritionPlanContract.GeneratedIngredient(it, 100f, "g", "100 g", "RAW", "HIGH", "FOOD") },
    )

    private fun planOf(vararg meals: NutritionPlanContract.GeneratedMeal) = NutritionPlanContract.Response(
        10,
        listOf(NutritionPlanContract.GeneratedDay(10, 400 * meals.size, 25f * meals.size, 40f * meals.size, 12f * meals.size, meals.toList())),
        NutritionPlanContract.AgentValidation(true, "ok"),
    )

    @Test
    fun genericTitlesObservedOnDeviceAreListedInOneNonBlockingWarning() {
        val response = planOf(
            meal("BREAKFAST", "Colazione leggera", "Yogurt greco", "Mela"),
            meal("SNACK", "Spuntino mattutino", "Mandorle"),
            meal("LUNCH", "Pranzo equilibrato", "Riso", "Pollo"),
            meal("DINNER", "Cena leggera", "Merluzzo", "Zucchine"),
        )
        val result = NutritionIntegrityValidator.validate(response, NutritionBusinessValidator.Targets(1600.0, 100.0, 160.0, 48.0), 4)

        val warning = result.warnings.single { it.code == "GENERIC_MEAL_TITLE" }
        assertEquals(Severity.WARNING, warning.severity)
        assertEquals("Colazione leggera; Spuntino mattutino; Pranzo equilibrato; Cena leggera", warning.mealTitle)
        assertEquals(4.0, warning.actual!!, 0.0)
        // Informative only: never an error, and it survives the appValidation v2 serialisation.
        assertTrue(result.issues.none { it.code == "GENERIC_MEAL_TITLE" })
        assertTrue(result.valid)
        val restored = NutritionIntegrityValidator.parse(result.toJson().toString())!!
        assertEquals("nutrition-integrity-v2", restored.validatorVersion)
        assertEquals(warning.mealTitle, restored.warnings.single { it.code == "GENERIC_MEAL_TITLE" }.mealTitle)
    }

    @Test
    fun specificTitlesThatNameTheirIngredientsDoNotWarn() {
        val response = planOf(
            meal("BREAKFAST", "Yogurt greco con mela", "Yogurt greco", "Mela"),
            meal("DINNER", "Merluzzo al forno con zucchine", "Merluzzo", "Zucchina", "Olio extravergine"),
        )
        assertNull(MealTitleQuality.warning(response))
        assertTrue(NutritionIntegrityValidator.validate(response, NutritionBusinessValidator.Targets(800.0, 50.0, 80.0, 24.0), 2).warnings.none { it.code == "GENERIC_MEAL_TITLE" })
    }

    @Test
    fun genericityUsesAClosedListOfSlotNamesAdjectivesAndConnectors() {
        listOf("Colazione", "Prima colazione leggera", "Pranzo sano e bilanciato", "Cena proteica", "Spuntino pomeridiano", "Merenda veloce", "Pasto completo")
            .forEach { assertTrue(it, MealTitleQuality.isGeneric(it)) }
        listOf("Yogurt greco con mela", "Colazione con uova", "Cena di merluzzo", "Pasta al pomodoro").forEach { assertFalse(it, MealTitleQuality.isGeneric(it)) }
    }

    @Test
    fun aTitleThatNamesNoneOfTheMealsIngredientsIsListedEvenIfNotGeneric() {
        val response = planOf(
            meal("LUNCH", "Piatto della nonna", "Riso", "Pollo"),
            meal("DINNER", "Pomodori ripieni", "Pomodoro", "Riso"),
        )
        // "Pomodori" matches "Pomodoro" (same word, different inflection): only the first title is listed.
        assertEquals(listOf("Piatto della nonna"), MealTitleQuality.weakTitles(response))
    }

    @Test
    fun listIsCappedAndNeverBlocksAPlanWhoseKcalAreInTarget() {
        val meals = listOf("Alfa", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta").map { meal("SNACK", it, "Riso") }
        val response = planOf(*meals.toTypedArray())
        val warning = MealTitleQuality.warning(response)!!
        assertTrue(warning.mealTitle!!.endsWith("; +2"))
        assertEquals(8.0, warning.actual!!, 0.0)
    }
}