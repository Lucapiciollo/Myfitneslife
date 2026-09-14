package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope
import org.json.JSONObject

/** Canonical contract for replacing one planned meal with calorie-equivalent alternatives. */
object MealAlternativeContract {
    const val SCHEMA_NAME = "myfitai_meal_alternatives_pipe_v1"
    val schemaJson: String = AiCompactEnvelope.schemaJson(MealAlternativeCompactContract.PROTOCOL)

    data class Ingredient(val name: String, val quantity: Float, val unit: String, val displayDose: String, val weightState: String, val nutritionConfidence: String, val category: String)
    data class Alternative(val title: String, val kcal: Int, val proteinG: Float, val carbsG: Float, val fatG: Float, val preparation: String, val reason: String, val ingredients: List<Ingredient>)
    data class Response(val alternatives: List<Alternative>, val agentValidation: NutritionPlanContract.AgentValidation)

    fun parse(jsonText: String): Response {
        val root = JSONObject(jsonText)
        if (root.has("data")) return MealAlternativeCompactContract.parse(jsonText)
        val source = root.getJSONArray("alternatives")
        val alternatives = buildList {
            for (i in 0 until source.length()) {
                val item = source.getJSONObject(i)
                val ingredientSource = item.getJSONArray("ingredients")
                val ingredients = buildList {
                    for (j in 0 until ingredientSource.length()) {
                        val ingredient = ingredientSource.getJSONObject(j)
                        add(Ingredient(ingredient.getString("name").trim(), ingredient.getDouble("quantity").toFloat(), ingredient.getString("unit").trim(), ingredient.getString("displayDose").trim(), ingredient.getString("weightState").trim(), ingredient.getString("nutritionConfidence").trim(), ingredient.getString("category").trim()))
                    }
                }
                add(Alternative(item.getString("title").trim(), item.getInt("kcal"), item.getDouble("proteinG").toFloat(), item.getDouble("carbsG").toFloat(), item.getDouble("fatG").toFloat(), item.getString("preparation").trim(), item.getString("reason").trim(), ingredients))
            }
        }
        val validation = root.getJSONObject("agentValidation")
        return Response(alternatives, NutritionPlanContract.AgentValidation(validation.getBoolean("valid"), validation.getString("notes").trim()))
    }

    fun validateBusiness(response: Response, sourceMeal: FoodMeal): Result<Unit> = runCatching {
        val targetKcal = requireNotNull(sourceMeal.kcal) { "SOURCE_MEAL_KCAL_MISSING" }
        require(response.alternatives.size == 5) { "EXACTLY_FIVE_ALTERNATIVES_REQUIRED" }
        val normalizedTitles = mutableSetOf<String>()
        response.alternatives.forEach { alternative ->
            require(alternative.title.isNotBlank()) { "EMPTY_TITLE" }
            require(normalizedTitles.add(alternative.title.lowercase())) { "DUPLICATE_ALTERNATIVE" }
            require(alternative.kcal == targetKcal) { "CALORIE_CONSTRAINT_VIOLATED" }
            require(alternative.proteinG >= 0f && alternative.proteinG.isFinite()) { "INVALID_PROTEIN" }
            require(alternative.carbsG >= 0f && alternative.carbsG.isFinite()) { "INVALID_CARBS" }
            require(alternative.fatG >= 0f && alternative.fatG.isFinite()) { "INVALID_FAT" }
            require(alternative.ingredients.isNotEmpty()) { "INGREDIENTS_REQUIRED" }
            alternative.ingredients.forEach { ingredient ->
                require(ingredient.name.isNotBlank() && ingredient.quantity > 0f && ingredient.quantity.isFinite()) { "INVALID_INGREDIENT" }
                require(ingredient.unit.isNotBlank() && ingredient.displayDose.isNotBlank()) { "INVALID_INGREDIENT_DOSE" }
            }
        }
    }
}
