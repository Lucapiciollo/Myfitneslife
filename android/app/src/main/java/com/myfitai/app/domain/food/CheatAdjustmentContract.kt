package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.json.JSONObject

/** Provider-neutral contract used to estimate a deviation and adapt only future meals of that day. */
object CheatAdjustmentContract {
    const val SCHEMA_NAME = "myfitai_cheat_adjustment_pipe_v1"
    val schemaJson: String = AiCompactEnvelope.schemaJson(CheatAdjustmentCompactContract.PROTOCOL)

    data class Estimate(
        val kcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
        val confidence: String,
        val notes: String,
    )

    data class ReplacementMeal(
        val sortOrder: Int,
        val type: String,
        val title: String,
        val timeMinutes: Int,
        val kcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
        val preparation: String,
        val ingredients: List<NutritionPlanContract.GeneratedIngredient>,
    )

    data class Response(
        val estimate: Estimate,
        val adaptationPossible: Boolean,
        val adaptationReason: String,
        val replacementMeals: List<ReplacementMeal>,
        val agentValidation: NutritionPlanContract.AgentValidation,
    )

    fun parse(jsonText: String): Response {
        val root = JSONObject(jsonText)
        if (root.has("data")) return CheatAdjustmentCompactContract.parse(jsonText)
        val estimateJson = root.getJSONObject("estimate")
        val replacementsJson = root.getJSONArray("replacementMeals")
        val replacements = buildList {
            for (i in 0 until replacementsJson.length()) {
                val meal = replacementsJson.getJSONObject(i)
                val ingredientsJson = meal.getJSONArray("ingredients")
                val ingredients = buildList {
                    for (j in 0 until ingredientsJson.length()) {
                        val ingredient = ingredientsJson.getJSONObject(j)
                        add(NutritionPlanContract.GeneratedIngredient(
                            name = ingredient.getString("name").trim(),
                            quantity = ingredient.getDouble("quantity").toFloat(),
                            unit = ingredient.getString("unit").trim(),
                            displayDose = ingredient.getString("displayDose").trim(),
                            weightState = ingredient.getString("weightState").trim(),
                            nutritionConfidence = ingredient.getString("nutritionConfidence").trim(),
                            category = ingredient.getString("category").trim(),
                        ))
                    }
                }
                add(ReplacementMeal(
                    sortOrder = meal.getInt("sortOrder"),
                    type = meal.getString("type").trim(),
                    title = meal.getString("title").trim(),
                    timeMinutes = meal.getInt("timeMinutes"),
                    kcal = meal.getInt("kcal"),
                    proteinG = meal.getDouble("proteinG").toFloat(),
                    carbsG = meal.getDouble("carbsG").toFloat(),
                    fatG = meal.getDouble("fatG").toFloat(),
                    preparation = meal.getString("preparation").trim(),
                    ingredients = ingredients,
                ))
            }
        }
        val agent = root.getJSONObject("agentValidation")
        return Response(
            estimate = Estimate(
                kcal = estimateJson.getInt("kcal"),
                proteinG = estimateJson.getDouble("proteinG").toFloat(),
                carbsG = estimateJson.getDouble("carbsG").toFloat(),
                fatG = estimateJson.getDouble("fatG").toFloat(),
                confidence = estimateJson.getString("confidence").trim(),
                notes = estimateJson.getString("notes").trim(),
            ),
            adaptationPossible = root.getBoolean("adaptationPossible"),
            adaptationReason = root.getString("adaptationReason").trim(),
            replacementMeals = replacements,
            agentValidation = NutritionPlanContract.AgentValidation(agent.getBoolean("valid"), agent.getString("notes")),
        )
    }

    fun validateBusiness(
        response: Response,
        lockedMeals: List<FoodMeal>,
        futureMeals: List<FoodMeal>,
        targets: NutritionBusinessValidator.Targets,
    ): Result<Unit> = runCatching {
        val estimate = response.estimate
        require(estimate.kcal > 0 && estimate.proteinG >= 0f && estimate.carbsG >= 0f && estimate.fatG >= 0f) { "CHEAT_ESTIMATE_INVALID" }
        require(estimate.confidence.lowercase() in setOf("low", "medium", "high")) { "CHEAT_CONFIDENCE_INVALID" }

        val locked = totals(lockedMeals)
        val minimumFutureKcal = futureMeals.size * 100.0
        val upperKcal = targets.kcal * (1.0 + NutritionBusinessValidator.DEFAULT_TOLERANCE)
        val canFitWithoutStarvation = locked.kcal + estimate.kcal + minimumFutureKcal <= upperKcal

        if (!response.adaptationPossible) {
            require(response.replacementMeals.isEmpty()) { "REPLACEMENTS_NOT_ALLOWED" }
            require(!canFitWithoutStarvation || futureMeals.isEmpty()) { "ADAPTATION_FALSE_WITHOUT_REASON" }
            return@runCatching
        }

        require(futureMeals.isNotEmpty()) { "NO_FUTURE_MEALS" }
        require(canFitWithoutStarvation) { "PUNITIVE_ADAPTATION_BLOCKED" }
        val expectedOrders = futureMeals.map { it.sortOrder }.toSet()
        require(response.replacementMeals.map { it.sortOrder }.toSet() == expectedOrders) { "FUTURE_MEAL_SET_MISMATCH" }
        require(response.replacementMeals.size == futureMeals.size) { "FUTURE_MEAL_COUNT_MISMATCH" }

        response.replacementMeals.forEach { replacement ->
            val original = futureMeals.first { it.sortOrder == replacement.sortOrder }
            require(replacement.timeMinutes == original.timeMinutes) { "MEAL_TIME_MUST_NOT_MOVE" }
            require(replacement.kcal >= 100) { "PUNITIVE_MEAL_TOO_SMALL" }
            require(replacement.type.isNotBlank() && replacement.title.isNotBlank()) { "MEAL_LABEL_MISSING" }
            require(replacement.proteinG >= 0f && replacement.carbsG >= 0f && replacement.fatG >= 0f) { "MEAL_MACROS_INVALID" }
            require(replacement.ingredients.isNotEmpty()) { "MEAL_WITHOUT_INGREDIENTS" }
            replacement.ingredients.forEach { ingredient ->
                require(ingredient.name.isNotBlank() && ingredient.quantity > 0f && ingredient.quantity.isFinite()) { "INGREDIENT_INVALID" }
                require(ingredient.unit.isNotBlank() && ingredient.displayDose.isNotBlank()) { "INGREDIENT_DOSE_MISSING" }
                require(ingredient.weightState.isNotBlank() && ingredient.nutritionConfidence.isNotBlank()) { "INGREDIENT_METADATA_MISSING" }
            }
        }

        val replacements = Totals(
            kcal = response.replacementMeals.sumOf { it.kcal.toDouble() },
            protein = response.replacementMeals.sumOf { it.proteinG.toDouble() },
            carbs = response.replacementMeals.sumOf { it.carbsG.toDouble() },
            fat = response.replacementMeals.sumOf { it.fatG.toDouble() },
        )
        val actual = NutritionBusinessValidator.Actuals(
            kcal = locked.kcal + estimate.kcal + replacements.kcal,
            proteinG = locked.protein + estimate.proteinG + replacements.protein,
            carbsG = locked.carbs + estimate.carbsG + replacements.carbs,
            fatG = locked.fat + estimate.fatG + replacements.fat,
        )
        require(NutritionBusinessValidator.validate(targets, actual).valid) { "ADAPTED_DAY_OUTSIDE_TARGETS" }
    }

    private data class Totals(val kcal: Double, val protein: Double, val carbs: Double, val fat: Double)
    private fun totals(meals: List<FoodMeal>): Totals = Totals(
        kcal = meals.sumOf { (it.kcal ?: 0).toDouble() },
        protein = meals.sumOf { (it.proteinG ?: 0f).toDouble() },
        carbs = meals.sumOf { (it.carbsG ?: 0f).toDouble() },
        fat = meals.sumOf { (it.fatG ?: 0f).toDouble() },
    )
}
