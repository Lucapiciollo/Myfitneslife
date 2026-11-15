package com.myfitai.app.domain.export

import com.myfitai.app.data.local.entity.MealEntity
import com.myfitai.app.data.local.entity.MealIngredientEntity
import com.myfitai.app.data.local.entity.MealPlanVersionEntity
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanVersion
import com.myfitai.app.domain.food.nutrition.DoseFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversione JSON <-> entita' di versioni, pasti e ingredienti del piano nell'export/backup (senza Room, testabile su JVM).
 *
 * Campi AGGIUNTIVI dello schema v3 (revisione 2, vedi [ProfileExportFormat.SCHEMA_REVISION]), tutti opzionali:
 * - versione: `catalogVersion` (solo se presente);
 * - pasto: `nutritionSource` (DECLARED | COMPUTED);
 * - ingrediente: `foodId`, `grams`, `kcal`, `proteinG`, `carbsG`, `fatG`, `fiberG`, `sodiumMg` (solo con snapshot).
 * Nessuna chiave esistente e' rimossa o rinominata. In lettura l'assenza dei campi nuovi (backup precedenti) e' normale:
 * pasti DECLARED, nessuno snapshot.
 */
internal object MealPlanBackupMapper {

    fun ingredientToJson(ing: MealIngredientEntity): JSONObject = JSONObject().apply {
        put("id", ing.id); put("name", ing.name); put("quantity", ing.quantity); put("unit", ing.unit)
        putNullable("displayDose", ing.displayDose); putNullable("weightState", ing.weightState)
        putNullable("nutritionConfidence", ing.nutritionConfidence); putNullable("category", ing.category); put("sortOrder", ing.sortOrder)
        // Snapshot nutrizionale (piani COMPUTED): chiavi presenti solo quando valorizzate.
        ing.foodId?.let { put("foodId", it) }
        ing.grams?.let { put("grams", it) }
        ing.kcal?.let { put("kcal", it) }
        ing.proteinG?.let { put("proteinG", it) }
        ing.carbsG?.let { put("carbsG", it) }
        ing.fatG?.let { put("fatG", it) }
        ing.fiberG?.let { put("fiberG", it) }
        ing.sodiumMg?.let { put("sodiumMg", it) }
    }

    fun ingredientFromJson(o: JSONObject, mealId: Long): MealIngredientEntity = MealIngredientEntity(
        mealId = mealId, name = o.optString("name"), quantity = o.optDouble("quantity").toFloat(),
        unit = o.optString("unit"), displayDose = o.stringOrNull("displayDose"), weightState = o.stringOrNull("weightState"),
        nutritionConfidence = o.stringOrNull("nutritionConfidence"), category = o.stringOrNull("category"), sortOrder = o.optInt("sortOrder"),
        foodId = o.stringOrNull("foodId"), grams = o.floatOrNull("grams"), kcal = o.floatOrNull("kcal"),
        proteinG = o.floatOrNull("proteinG"), carbsG = o.floatOrNull("carbsG"), fatG = o.floatOrNull("fatG"),
        fiberG = o.floatOrNull("fiberG"), sodiumMg = o.floatOrNull("sodiumMg"),
    )

    fun mealToJson(meal: MealEntity, ingredients: JSONArray): JSONObject = JSONObject().apply {
        put("id", meal.id); put("sortOrder", meal.sortOrder); put("type", meal.type); put("title", meal.title)
        putNullable("timeMinutes", meal.timeMinutes); putNullable("kcal", meal.kcal); putNullable("proteinG", meal.proteinG)
        putNullable("carbsG", meal.carbsG); putNullable("fatG", meal.fatG); putNullable("preparation", meal.preparation)
        put("nutritionSource", meal.nutritionSource)
        put("ingredients", ingredients)
    }

    fun mealFromJson(o: JSONObject, dayId: Long): MealEntity = MealEntity(
        dayId = dayId, sortOrder = o.getInt("sortOrder"), type = o.optString("type"), title = o.optString("title"),
        timeMinutes = o.intOrNull("timeMinutes"), kcal = o.intOrNull("kcal"), proteinG = o.floatOrNull("proteinG"),
        carbsG = o.floatOrNull("carbsG"), fatG = o.floatOrNull("fatG"), preparation = o.stringOrNull("preparation"),
        nutritionSource = o.stringOrNull("nutritionSource") ?: FoodMeal.NUTRITION_SOURCE_DECLARED,
    )

    fun versionToJson(version: MealPlanVersionEntity, days: JSONArray): JSONObject = JSONObject().apply {
        put("id", version.id); put("versionNumber", version.versionNumber); put("createdAtEpochMillis", version.createdAtEpochMillis)
        put("source", version.source); putNullable("reason", version.reason); putNullable("targetKcal", version.targetKcal)
        putNullable("targetProteinG", version.targetProteinG); putNullable("targetCarbsG", version.targetCarbsG)
        putNullable("targetFatG", version.targetFatG); putNullable("appValidationJson", version.appValidationJson)
        version.catalogVersion?.let { put("catalogVersion", it) }
        put("days", days)
    }

    fun versionFromJson(o: JSONObject, planId: Long): MealPlanVersionEntity = MealPlanVersionEntity(
        planId = planId, versionNumber = o.getInt("versionNumber"), createdAtEpochMillis = o.getLong("createdAtEpochMillis"),
        source = o.optString("source"), reason = o.stringOrNull("reason"), targetKcal = o.intOrNull("targetKcal"),
        targetProteinG = o.floatOrNull("targetProteinG"), targetCarbsG = o.floatOrNull("targetCarbsG"), targetFatG = o.floatOrNull("targetFatG"),
        appValidationJson = o.stringOrNull("appValidationJson"),
        catalogVersion = o.stringOrNull("catalogVersion"),
    )

    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
    private fun JSONObject.floatOrNull(key: String): Float? = if (!has(key) || isNull(key)) null else optDouble(key).toFloat()
}

/**
 * Testi di export per i piani con valori calcolati dall'app (COMPUTED). I piani DECLARED non ricevono nessun testo aggiuntivo.
 * Sono costanti dell'export, non risorse della UI.
 */
internal object ExportNutritionText {
    const val COMPUTED_VALUES_NOTE = "Valori calcolati dall'app da catalogo USDA"

    /** Nota di provenienza dei valori nutrizionali di una versione del piano; null se nessun pasto e' COMPUTED. */
    fun planNote(version: FoodPlanVersion): String? {
        val meals = version.days.flatMap { it.meals }
        val computed = meals.count { it.nutritionSource == FoodMeal.NUTRITION_SOURCE_COMPUTED }
        if (computed == 0) return null
        val catalog = version.catalogVersion?.takeIf { it.isNotBlank() }?.let { " (catalogo v$it)" }.orEmpty()
        return if (computed == meals.size) COMPUTED_VALUES_NOTE + catalog
        else "$COMPUTED_VALUES_NOTE$catalog: pasti calcolati $computed su ${meals.size}"
    }

    /** Dettaglio aggiuntivo di un ingrediente COMPUTED con snapshot, es. "(9 g, 80 kcal)"; null per gli altri. */
    fun ingredientDetail(meal: FoodMeal, ingredient: FoodIngredient, dose: String): String? {
        if (meal.nutritionSource != FoodMeal.NUTRITION_SOURCE_COMPUTED || ingredient.foodId == null) return null
        val parts = mutableListOf<String>()
        ingredient.grams?.takeIf { it.isFinite() && it > 0f }?.let { grams ->
            val text = DoseFormatter.formatGrams(grams.toDouble())
            if (!dose.trim().equals(text, ignoreCase = true)) parts += text
        }
        ingredient.kcal?.takeIf { it.isFinite() }?.let { parts += "${Math.round(it)} kcal" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ", "(", ")")
    }
}
