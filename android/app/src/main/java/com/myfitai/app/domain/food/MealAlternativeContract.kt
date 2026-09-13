package com.myfitai.app.domain.food

import org.json.JSONObject

/** Canonical contract for replacing one planned meal with calorie-equivalent alternatives. */
object MealAlternativeContract {
    const val SCHEMA_NAME = "myfitai_meal_alternatives_v1"

    val schemaJson: String = JSONObject(
        """
        {
          "type":"object",
          "additionalProperties":false,
          "properties":{
            "alternatives":{
              "type":"array",
              "minItems":5,
              "maxItems":5,
              "items":{
                "type":"object",
                "additionalProperties":false,
                "properties":{
                  "title":{"type":"string"},
                  "kcal":{"type":"integer"},
                  "proteinG":{"type":"number"},
                  "carbsG":{"type":"number"},
                  "fatG":{"type":"number"},
                  "preparation":{"type":"string"},
                  "reason":{"type":"string"},
                  "ingredients":{
                    "type":"array",
                    "minItems":1,
                    "items":{
                      "type":"object",
                      "additionalProperties":false,
                      "properties":{
                        "name":{"type":"string"},
                        "quantity":{"type":"number"},
                        "unit":{"type":"string"},
                        "displayDose":{"type":"string"},
                        "weightState":{"type":"string"},
                        "nutritionConfidence":{"type":"string"},
                        "category":{"type":"string"}
                      },
                      "required":["name","quantity","unit","displayDose","weightState","nutritionConfidence","category"]
                    }
                  }
                },
                "required":["title","kcal","proteinG","carbsG","fatG","preparation","reason","ingredients"]
              }
            },
            "agentValidation":{
              "type":"object",
              "additionalProperties":false,
              "properties":{"valid":{"type":"boolean"},"notes":{"type":"string"}},
              "required":["valid","notes"]
            }
          },
          "required":["alternatives","agentValidation"]
        }
        """.trimIndent()
    ).toString()

    data class Ingredient(
        val name: String,
        val quantity: Float,
        val unit: String,
        val displayDose: String,
        val weightState: String,
        val nutritionConfidence: String,
        val category: String,
    )

    data class Alternative(
        val title: String,
        val kcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
        val preparation: String,
        val reason: String,
        val ingredients: List<Ingredient>,
    )

    data class Response(
        val alternatives: List<Alternative>,
        val agentValidation: NutritionPlanContract.AgentValidation,
    )

    fun parse(jsonText: String): Response {
        val root = JSONObject(jsonText)
        val source = root.getJSONArray("alternatives")
        val alternatives = buildList {
            for (i in 0 until source.length()) {
                val item = source.getJSONObject(i)
                val ingredientSource = item.getJSONArray("ingredients")
                val ingredients = buildList {
                    for (j in 0 until ingredientSource.length()) {
                        val ingredient = ingredientSource.getJSONObject(j)
                        add(
                            Ingredient(
                                name = ingredient.getString("name").trim(),
                                quantity = ingredient.getDouble("quantity").toFloat(),
                                unit = ingredient.getString("unit").trim(),
                                displayDose = ingredient.getString("displayDose").trim(),
                                weightState = ingredient.getString("weightState").trim(),
                                nutritionConfidence = ingredient.getString("nutritionConfidence").trim(),
                                category = ingredient.getString("category").trim(),
                            )
                        )
                    }
                }
                add(
                    Alternative(
                        title = item.getString("title").trim(),
                        kcal = item.getInt("kcal"),
                        proteinG = item.getDouble("proteinG").toFloat(),
                        carbsG = item.getDouble("carbsG").toFloat(),
                        fatG = item.getDouble("fatG").toFloat(),
                        preparation = item.getString("preparation").trim(),
                        reason = item.getString("reason").trim(),
                        ingredients = ingredients,
                    )
                )
            }
        }
        val validation = root.getJSONObject("agentValidation")
        return Response(
            alternatives = alternatives,
            agentValidation = NutritionPlanContract.AgentValidation(
                valid = validation.getBoolean("valid"),
                notes = validation.getString("notes").trim(),
            ),
        )
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
