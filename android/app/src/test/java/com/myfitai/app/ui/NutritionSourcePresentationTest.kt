package com.myfitai.app.ui

import com.myfitai.app.R
import com.myfitai.app.domain.food.FoodCatalogPlanBuilder
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanVersion
import com.myfitai.app.ui.NutritionSourcePresentation.Basis
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L6a: testi e righe secondarie per i valori nutrizionali dichiarati dal modello o calcolati dal catalogo. */
class NutritionSourcePresentationTest {
    private val resDir: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun strings(): Map<String, String> {
        val xml = File(resDir, "values/strings.xml").readText()
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private fun ingredient(
        name: String = "Petto di pollo",
        kcal: Float? = null,
        protein: Float? = null,
        carbs: Float? = null,
        fat: Float? = null,
        confidence: String? = null,
    ) = FoodIngredient(
        id = 1, mealId = 1, name = name, quantity = 150f, unit = "g", displayDose = "150 g", weightState = "raw",
        nutritionConfidence = confidence, category = "protein", sortOrder = 0,
        kcal = kcal, proteinG = protein, carbsG = carbs, fatG = fat,
    )

    private fun meal(source: String, ingredients: List<FoodIngredient> = emptyList()) = FoodMeal(
        id = 1, dayId = 1, sortOrder = 0, type = "Pranzo", title = "Pollo", timeMinutes = 780,
        kcal = 500, proteinG = 40f, carbsG = 50f, fatG = 10f, preparation = null,
        ingredients = ingredients, nutritionSource = source,
    )

    private fun version(vararg sources: String, validation: String? = null) = FoodPlanVersion(
        id = 1, versionNumber = 1, createdAtEpochMillis = 0, source = "AI", reason = null,
        targetKcal = null, targetProteinG = null, targetCarbsG = null, targetFatG = null,
        days = listOf(FoodPlanDay(1, 100, null, null, null, null, sources.map { meal(it) })),
        appValidationJson = validation,
    )

    @Test
    fun declaredTextsAreExactlyTheHistoricalOnes() {
        val s = strings()
        assertEquals("Valori nutrizionali stimati", s["food_plan_nutrition_info_title_declared"])
        assertEquals(
            "Le calorie e i macronutrienti indicati nel piano alimentare sono stime elaborate dall'intelligenza artificiale " +
                "sulla base degli alimenti e delle quantità suggerite. I valori effettivi possono variare in funzione dei prodotti " +
                "utilizzati, delle porzioni e delle modalità di preparazione. Il simbolo \u2248 indica un valore approssimativo " +
                "e non una misurazione esatta.",
            s["food_plan_nutrition_info_body_declared"],
        )
        assertEquals("Informazioni sui valori nutrizionali stimati", s["food_plan_nutrition_info_icon_declared"])
    }

    @Test
    fun declaredBasisPicksTheDeclaredResources() {
        // Piani storici e pasti senza origine nota: nessun cambiamento rispetto a oggi.
        listOf(null, "", FoodMeal.NUTRITION_SOURCE_DECLARED, "ALTRO").forEach { source ->
            assertEquals(Basis.DECLARED, NutritionSourcePresentation.basisOf(source))
        }
        assertEquals(R.string.food_plan_nutrition_info_title_declared, NutritionSourcePresentation.infoTitleRes(Basis.DECLARED))
        assertEquals(R.string.food_plan_nutrition_info_body_declared, NutritionSourcePresentation.infoBodyRes(Basis.DECLARED))
        assertEquals(R.string.food_plan_nutrition_info_icon_declared, NutritionSourcePresentation.infoIconDescriptionRes(Basis.DECLARED))
        assertEquals(Basis.DECLARED, NutritionSourcePresentation.planBasis(null))
        assertEquals(Basis.DECLARED, NutritionSourcePresentation.planBasis(version("DECLARED", "DECLARED")))
        assertEquals(Basis.DECLARED, NutritionSourcePresentation.basisOf(emptyList()))
    }

    @Test
    fun computedPlanExplainsTheCatalogWithoutAiEstimate() {
        assertEquals(Basis.COMPUTED, NutritionSourcePresentation.planBasis(version("COMPUTED", "COMPUTED")))
        assertEquals(R.string.food_plan_nutrition_info_body_computed, NutritionSourcePresentation.infoBodyRes(Basis.COMPUTED))
        val s = strings()
        val body = s.getValue("food_plan_nutrition_info_body_computed")
        assertTrue(body.contains("calcolati dall'app"))
        assertTrue(body.contains("USDA FoodData Central"))
        assertTrue(body.contains("approssimati"))
        assertTrue(body.contains("cottura, marca e porzioni reali possono variare"))
        assertFalse(body.contains("intelligenza artificiale"))
        assertFalse(s.getValue("food_plan_nutrition_info_title_computed").contains("stimat"))
        assertNotEquals(
            s.getValue("food_plan_nutrition_info_body_declared"),
            body,
        )
    }

    @Test
    fun mixedPlanNamesBothOrigins() {
        assertEquals(Basis.MIXED, NutritionSourcePresentation.planBasis(version("COMPUTED", "DECLARED")))
        val body = strings().getValue("food_plan_nutrition_info_body_mixed")
        assertTrue(body.contains("calcolati dall'app"))
        assertTrue(body.contains("intelligenza artificiale"))
    }

    @Test
    fun alternativesAreComputedWhenTheyCarryACatalogVersionOrComeFromAComputedMeal() {
        assertTrue(NutritionSourcePresentation.alternativeIsComputed(3, null))
        assertTrue(NutritionSourcePresentation.alternativeIsComputed(null, "COMPUTED"))
        assertFalse(NutritionSourcePresentation.alternativeIsComputed(null, null))
        assertFalse(NutritionSourcePresentation.alternativeIsComputed(null, "DECLARED"))
    }

    @Test
    fun ingredientLineShowsKcalAndMacrosWithoutBreakableWords() {
        val line = NutritionSourcePresentation.ingredientNutritionLine(
            ingredient(kcal = 165.4f, protein = 31f, carbs = 0f, fat = 3.6f),
        )
        assertEquals(
            "\u2248\u00A0165\u00A0kcal \u00B7 P\u00A031\u00A0g \u00B7 C\u00A00\u00A0g \u00B7 G\u00A03,6\u00A0g",
            line,
        )
        // Si va a capo solo sui separatori: dentro ogni segmento ci sono soli spazi non separabili.
        line!!.split(" \u00B7 ").forEach { segment -> assertFalse(segment.contains(' ')) }
    }

    @Test
    fun ingredientLineSkipsMissingValuesAndIsNullWithoutSnapshot() {
        assertNull(NutritionSourcePresentation.ingredientNutritionLine(ingredient()))
        assertEquals(
            "\u2248\u00A090\u00A0kcal",
            NutritionSourcePresentation.ingredientNutritionLine(ingredient(kcal = 90f)),
        )
        assertEquals(
            "P\u00A02,5\u00A0g \u00B7 G\u00A00,5\u00A0g",
            NutritionSourcePresentation.ingredientNutritionLine(ingredient(protein = 2.5f, fat = 0.5f)),
        )
    }

    @Test
    fun sourceLineOnlyForComputedMealsAndMentionsLabelEstimates() {
        assertNull(NutritionSourcePresentation.mealSourceRes(meal("DECLARED", listOf(ingredient(confidence = "C")))))
        assertEquals(
            R.string.meal_detail_source_catalog,
            NutritionSourcePresentation.mealSourceRes(meal("COMPUTED", listOf(ingredient(confidence = "A"), ingredient(confidence = "B")))),
        )
        assertEquals(
            R.string.meal_detail_source_catalog_label_estimate,
            NutritionSourcePresentation.mealSourceRes(meal("COMPUTED", listOf(ingredient(confidence = "A"), ingredient(confidence = "C")))),
        )
        val s = strings()
        assertEquals("Fonte: catalogo alimentare (USDA)", s["meal_detail_source_catalog"])
        assertTrue(s.getValue("meal_detail_source_catalog_label_estimate").contains("stima è da etichetta"))
    }

    private fun validation(code: String = FoodCatalogPlanBuilder.PROTEIN_BELOW_TARGET, day: Long = 100, expected: String = "110.0", actual: String = "92.4") =
        """{"available":true,"valid":true,"validatorVersion":"2","warnings":[{"code":"$code","severity":"WARNING","message":"m","dayEpochDay":$day,"expected":$expected,"actual":$actual}],"issues":[]}"""

    @Test
    fun proteinNoteIsReadFromTheWarningOfTheSelectedDay() {
        val note = NutritionSourcePresentation.proteinBelowTarget(validation(), 100)
        assertNotNull(note)
        assertEquals("92\u00A0g", note!!.actual)
        assertEquals("110\u00A0g", note.target)
        assertTrue(note.hasValues)
        val s = strings()
        assertEquals("Proteine sotto l'obiettivo di giornata: %1\$s su %2\$s.", s["food_plan_protein_below_target"])
        assertFalse(s.getValue("food_plan_protein_below_target").contains("rror"))
    }

    @Test
    fun proteinNoteIgnoresOtherDaysOtherCodesAndBrokenDocuments() {
        assertNull(NutritionSourcePresentation.proteinBelowTarget(validation(), 101))
        assertNull(NutritionSourcePresentation.proteinBelowTarget(validation(code = "GENERIC_MEAL_TITLE"), 100))
        assertNull(NutritionSourcePresentation.proteinBelowTarget(null, 100))
        assertNull(NutritionSourcePresentation.proteinBelowTarget("", 100))
        assertNull(NutritionSourcePresentation.proteinBelowTarget("{non json", 100))
        assertNull(NutritionSourcePresentation.proteinBelowTarget("""{"warnings":[]}""", 100))
    }

    @Test
    fun proteinNoteWithoutNumbersFallsBackToThePlainSentence() {
        val note = NutritionSourcePresentation.proteinBelowTarget(validation(expected = "null", actual = "null"), 100)
        assertNotNull(note)
        assertFalse(note!!.hasValues)
        assertEquals("Proteine sotto l'obiettivo di giornata.", strings()["food_plan_protein_below_target_plain"])
    }
}
