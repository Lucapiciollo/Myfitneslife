package com.myfitai.app.domain.export

import com.myfitai.app.data.local.entity.MealEntity
import com.myfitai.app.data.local.entity.MealIngredientEntity
import com.myfitai.app.data.local.entity.MealPlanVersionEntity
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanVersion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip export -> restore (lato JSON <-> entita') dei campi COMPUTED, e compatibilita' con i backup precedenti. */
class MealPlanBackupMapperTest {

    private val computedIngredient = MealIngredientEntity(
        id = 11, mealId = 5, name = "Olio extravergine d'oliva", quantity = 10f, unit = "ml", displayDose = "2 cucchiaini",
        weightState = null, nutritionConfidence = "A", category = "Olio, spezie e condimenti", sortOrder = 2,
        foodId = "it.olio_evo", grams = 9.1f, kcal = 80.4f, proteinG = 0f, carbsG = 0f, fatG = 9.1f, fiberG = 0f, sodiumMg = 0.2f,
    )
    private val declaredIngredient = MealIngredientEntity(
        id = 12, mealId = 6, name = "Riso basmati", quantity = 80f, unit = "g", displayDose = "80 g",
        weightState = "crudo", nutritionConfidence = "MEDIUM", category = "Cereali", sortOrder = 0,
    )

    private fun reparsed(o: JSONObject) = JSONObject(o.toString(2))

    @Test
    fun computedIngredient_roundTripKeepsSnapshot() {
        val json = reparsed(MealPlanBackupMapper.ingredientToJson(computedIngredient))
        assertEquals("it.olio_evo", json.getString("foodId"))
        assertEquals(9.1, json.getDouble("grams"), 0.0001)
        val restored = MealPlanBackupMapper.ingredientFromJson(json, mealId = 5)
        assertEquals(computedIngredient.copy(id = 0), restored)
    }

    @Test
    fun declaredIngredient_hasNoNewKeysAndRoundTripsAsBefore() {
        val json = reparsed(MealPlanBackupMapper.ingredientToJson(declaredIngredient))
        listOf("foodId", "grams", "kcal", "proteinG", "carbsG", "fatG", "fiberG", "sodiumMg").forEach { assertFalse("chiave $it", json.has(it)) }
        // chiavi storiche tutte presenti
        listOf("id", "name", "quantity", "unit", "displayDose", "weightState", "nutritionConfidence", "category", "sortOrder").forEach { assertTrue(it, json.has(it)) }
        assertEquals(declaredIngredient.copy(id = 0), MealPlanBackupMapper.ingredientFromJson(json, mealId = 6))
    }

    @Test
    fun meal_nutritionSourceRoundTripsAndDefaultsToDeclaredWhenAbsent() {
        val computed = MealEntity(id = 5, dayId = 1, sortOrder = 0, type = "Pranzo", title = "Pasta", timeMinutes = 780, kcal = 700, proteinG = 30f, carbsG = 90f, fatG = 20f, preparation = null, nutritionSource = "COMPUTED")
        val json = reparsed(MealPlanBackupMapper.mealToJson(computed, JSONArray()))
        assertEquals("COMPUTED", json.getString("nutritionSource"))
        assertEquals(computed.copy(id = 0, dayId = 9), MealPlanBackupMapper.mealFromJson(json, dayId = 9))

        // backup precedente: nessuna chiave nutritionSource
        val old = reparsed(MealPlanBackupMapper.mealToJson(computed.copy(nutritionSource = "DECLARED"), JSONArray())).apply { remove("nutritionSource") }
        assertEquals("DECLARED", MealPlanBackupMapper.mealFromJson(old, dayId = 9).nutritionSource)
    }

    @Test
    fun version_catalogVersionRoundTripsAndIsAbsentForDeclared() {
        val base = MealPlanVersionEntity(id = 3, planId = 1, versionNumber = 4, createdAtEpochMillis = 99, source = "GEMINI", reason = null, targetKcal = 2800, targetProteinG = 180f, targetCarbsG = 370f, targetFatG = 62f, appValidationJson = "{}")
        val computedJson = reparsed(MealPlanBackupMapper.versionToJson(base.copy(catalogVersion = "1"), JSONArray()))
        assertEquals("1", computedJson.getString("catalogVersion"))
        assertEquals(base.copy(id = 0, planId = 8, catalogVersion = "1"), MealPlanBackupMapper.versionFromJson(computedJson, planId = 8))

        val declaredJson = reparsed(MealPlanBackupMapper.versionToJson(base, JSONArray()))
        assertFalse(declaredJson.has("catalogVersion"))
        assertNull(MealPlanBackupMapper.versionFromJson(declaredJson, planId = 8).catalogVersion)
        assertEquals(base.copy(id = 0, planId = 8), MealPlanBackupMapper.versionFromJson(declaredJson, planId = 8))
    }

    @Test
    fun exportRoot_keepsSchemaV3AndDeclaresRevision() {
        assertEquals("myfitai_profile_export_v3", ProfileExportFormat.CURRENT_SCHEMA)
        assertTrue(ProfileExportFormat.isRestorable("myfitai_profile_export_v3"))
        assertTrue(ProfileExportFormat.isRestorable("myfitai_profile_export_v2"))
        assertEquals(2, ProfileExportFormat.SCHEMA_REVISION)
    }

    private fun meal(source: String, ingredients: List<FoodIngredient> = emptyList()) = FoodMeal(
        id = 1, dayId = 1, sortOrder = 0, type = "Pranzo", title = "t", timeMinutes = 700, kcal = 500, proteinG = 30f, carbsG = 50f, fatG = 10f,
        preparation = null, ingredients = ingredients, nutritionSource = source,
    )

    private fun version(catalogVersion: String?, vararg meals: FoodMeal) = FoodPlanVersion(
        id = 1, versionNumber = 1, createdAtEpochMillis = 1, source = "TEST", reason = null, targetKcal = 2000, targetProteinG = 1f, targetCarbsG = 1f, targetFatG = 1f,
        days = listOf(FoodPlanDay(id = 1, dateEpochDay = 1, totalKcal = 2000, proteinG = 1f, carbsG = 1f, fatG = 1f, meals = meals.toList())),
        catalogVersion = catalogVersion,
    )

    @Test
    fun planNote_onlyForComputedPlans() {
        assertNull(ExportNutritionText.planNote(version(null, meal("DECLARED"), meal("DECLARED"))))
        assertEquals(
            "Valori calcolati dall'app da catalogo USDA (catalogo v1)",
            ExportNutritionText.planNote(version("1", meal("COMPUTED"), meal("COMPUTED"))),
        )
        assertEquals(
            "Valori calcolati dall'app da catalogo USDA: pasti calcolati 1 su 2",
            ExportNutritionText.planNote(version(null, meal("COMPUTED"), meal("DECLARED"))),
        )
    }

    @Test
    fun ingredientDetail_onlyForComputedIngredientsWithSnapshot() {
        val ing = FoodIngredient(
            id = 1, mealId = 1, name = "Olio", quantity = 10f, unit = "ml", displayDose = "2 cucchiaini", weightState = null,
            nutritionConfidence = "A", category = null, sortOrder = 0, foodId = "it.olio_evo", grams = 9f, kcal = 79.6f,
        )
        assertEquals("(9 g, 80 kcal)", ExportNutritionText.ingredientDetail(meal("COMPUTED"), ing, "2 cucchiaini"))
        // dose gia' in grammi: non si ripete
        assertEquals("(80 kcal)", ExportNutritionText.ingredientDetail(meal("COMPUTED"), ing, "9 g"))
        assertNull(ExportNutritionText.ingredientDetail(meal("DECLARED"), ing, "2 cucchiaini"))
        assertNull(ExportNutritionText.ingredientDetail(meal("COMPUTED"), ing.copy(foodId = null), "2 cucchiaini"))
    }
}
