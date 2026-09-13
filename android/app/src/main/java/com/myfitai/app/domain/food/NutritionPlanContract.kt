package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.json.JSONObject
import java.time.LocalDate

/** Canonical provider-neutral contract for weekly nutrition generation. */
object NutritionPlanContract {
    const val SCHEMA_NAME = "myfitai_weekly_nutrition_plan_v1"

    val schemaJson: String = JSONObject(
        """
        {
          "type":"object",
          "additionalProperties":false,
          "properties":{
            "weekStartEpochDay":{"type":"integer"},
            "days":{
              "type":"array","minItems":7,"maxItems":7,
              "items":{
                "type":"object","additionalProperties":false,
                "properties":{
                  "dateEpochDay":{"type":"integer"},
                  "totalKcal":{"type":"integer"},
                  "proteinG":{"type":"number"},
                  "carbsG":{"type":"number"},
                  "fatG":{"type":"number"},
                  "meals":{
                    "type":"array","minItems":3,"maxItems":8,
                    "items":{
                      "type":"object","additionalProperties":false,
                      "properties":{
                        "type":{"type":"string"},
                        "title":{"type":"string"},
                        "timeMinutes":{"type":"integer"},
                        "kcal":{"type":"integer"},
                        "proteinG":{"type":"number"},
                        "carbsG":{"type":"number"},
                        "fatG":{"type":"number"},
                        "preparation":{"type":"string"},
                        "ingredients":{
                          "type":"array","minItems":1,
                          "items":{
                            "type":"object","additionalProperties":false,
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
                      "required":["type","title","timeMinutes","kcal","proteinG","carbsG","fatG","preparation","ingredients"]
                    }
                  }
                },
                "required":["dateEpochDay","totalKcal","proteinG","carbsG","fatG","meals"]
              }
            },
            "agentValidation":{
              "type":"object","additionalProperties":false,
              "properties":{"valid":{"type":"boolean"},"notes":{"type":"string"}},
              "required":["valid","notes"]
            }
          },
          "required":["weekStartEpochDay","days","agentValidation"]
        }
        """.trimIndent()
    ).toString()

    data class AgentValidation(val valid: Boolean, val notes: String)
    data class Response(
        val weekStartEpochDay: Long,
        val days: List<GeneratedDay>,
        val agentValidation: AgentValidation,
    )
    data class GeneratedDay(
        val dateEpochDay: Long,
        val totalKcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
        val meals: List<GeneratedMeal>,
    )
    data class GeneratedMeal(
        val type: String,
        val title: String,
        val timeMinutes: Int,
        val kcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
        val preparation: String,
        val ingredients: List<GeneratedIngredient>,
    )
    data class GeneratedIngredient(
        val name: String,
        val quantity: Float,
        val unit: String,
        val displayDose: String,
        val weightState: String,
        val nutritionConfidence: String,
        val category: String,
    )

    fun parse(jsonText: String): Response {
        val root = JSONObject(jsonText)
        val daysJson = root.getJSONArray("days")
        val days = buildList {
            for (i in 0 until daysJson.length()) {
                val day = daysJson.getJSONObject(i)
                val mealsJson = day.getJSONArray("meals")
                val meals = buildList {
                    for (m in 0 until mealsJson.length()) {
                        val meal = mealsJson.getJSONObject(m)
                        val ingredientsJson = meal.getJSONArray("ingredients")
                        val ingredients = buildList {
                            for (j in 0 until ingredientsJson.length()) {
                                val ingredient = ingredientsJson.getJSONObject(j)
                                add(GeneratedIngredient(
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
                        add(GeneratedMeal(
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
                add(GeneratedDay(
                    dateEpochDay = day.getLong("dateEpochDay"),
                    totalKcal = day.getInt("totalKcal"),
                    proteinG = day.getDouble("proteinG").toFloat(),
                    carbsG = day.getDouble("carbsG").toFloat(),
                    fatG = day.getDouble("fatG").toFloat(),
                    meals = meals,
                ))
            }
        }
        val agent = root.getJSONObject("agentValidation")
        return Response(
            weekStartEpochDay = root.getLong("weekStartEpochDay"),
            days = days,
            agentValidation = AgentValidation(agent.getBoolean("valid"), agent.getString("notes")),
        )
    }

    fun validateBusiness(
        response: Response,
        expectedWeekStart: LocalDate,
        targets: NutritionBusinessValidator.Targets,
    ): Result<Unit> = runCatching {
        require(response.weekStartEpochDay == expectedWeekStart.toEpochDay()) { "WEEK_START_MISMATCH" }
        require(response.days.size == 7) { "WEEK_MUST_HAVE_7_DAYS" }
        val expectedDates = (0L..6L).map { expectedWeekStart.plusDays(it).toEpochDay() }.toSet()
        require(response.days.map { it.dateEpochDay }.toSet() == expectedDates) { "WEEK_DATES_INVALID" }

        response.days.forEach { day ->
            require(day.meals.isNotEmpty()) { "DAY_WITHOUT_MEALS" }
            require(day.totalKcal > 0 && day.proteinG > 0f && day.carbsG >= 0f && day.fatG > 0f) { "DAY_MACROS_INVALID" }
            val appValidation = NutritionBusinessValidator.validate(
                targets,
                NutritionBusinessValidator.Actuals(
                    kcal = day.totalKcal.toDouble(),
                    proteinG = day.proteinG.toDouble(),
                    carbsG = day.carbsG.toDouble(),
                    fatG = day.fatG.toDouble(),
                ),
            )
            require(appValidation.valid) { "TARGET_TOLERANCE_EXCEEDED" }

            val mealKcal = day.meals.sumOf { it.kcal }
            val mealProtein = day.meals.sumOf { it.proteinG.toDouble() }
            val mealCarbs = day.meals.sumOf { it.carbsG.toDouble() }
            val mealFat = day.meals.sumOf { it.fatG.toDouble() }
            val consistency = NutritionBusinessValidator.validate(
                NutritionBusinessValidator.Targets(day.totalKcal.toDouble(), day.proteinG.toDouble(), day.carbsG.toDouble(), day.fatG.toDouble()),
                NutritionBusinessValidator.Actuals(mealKcal.toDouble(), mealProtein, mealCarbs, mealFat),
            )
            require(consistency.valid) { "MEAL_TOTALS_INCONSISTENT" }

            day.meals.forEach { meal ->
                require(meal.type.isNotBlank() && meal.title.isNotBlank()) { "MEAL_LABEL_MISSING" }
                require(meal.timeMinutes in 0..1439) { "MEAL_TIME_INVALID" }
                require(meal.kcal >= 0 && meal.proteinG >= 0f && meal.carbsG >= 0f && meal.fatG >= 0f) { "MEAL_MACROS_INVALID" }
                meal.ingredients.forEach { ingredient ->
                    require(ingredient.name.isNotBlank()) { "INGREDIENT_NAME_MISSING" }
                    require(ingredient.quantity > 0f && ingredient.quantity.isFinite()) { "INGREDIENT_QUANTITY_INVALID" }
                    require(ingredient.unit.isNotBlank() && ingredient.displayDose.isNotBlank()) { "INGREDIENT_DOSE_MISSING" }
                    require(ingredient.weightState.isNotBlank() && ingredient.nutritionConfidence.isNotBlank()) { "INGREDIENT_METADATA_MISSING" }
                }
            }
        }
    }
}
