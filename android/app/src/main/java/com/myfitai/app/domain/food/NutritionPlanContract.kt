package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.json.JSONObject
import java.time.LocalDate

/** Canonical provider-neutral contract for weekly nutrition generation. */
object NutritionPlanContract {
    const val SCHEMA_NAME = "myfitai_weekly_nutrition_plan_v2"

    val schemaJson: String = JSONObject(
        """
        {
          "type":"object","additionalProperties":false,
          "properties":{
            "weekStartEpochDay":{"type":"integer"},
            "days":{"type":"array","minItems":7,"maxItems":7,"items":{
              "type":"object","additionalProperties":false,
              "properties":{
                "dateEpochDay":{"type":"integer"},"totalKcal":{"type":"integer"},"proteinG":{"type":"number"},"carbsG":{"type":"number"},"fatG":{"type":"number"},
                "hydrationNote":{"type":"string"},
                "supplements":{"type":"array","maxItems":3,"items":{
                  "type":"object","additionalProperties":false,
                  "properties":{
                    "kind":{"type":"string","enum":["PROTEIN_POWDER","CREATINE"]},
                    "name":{"type":"string"},"dose":{"type":"number"},"unit":{"type":"string"},"timeMinutes":{"type":"integer"},
                    "kcal":{"type":"integer"},"proteinG":{"type":"number"},"carbsG":{"type":"number"},"fatG":{"type":"number"},"notes":{"type":"string"}
                  },
                  "required":["kind","name","dose","unit","timeMinutes","kcal","proteinG","carbsG","fatG","notes"]
                }},
                "meals":{"type":"array","minItems":3,"maxItems":8,"items":{
                  "type":"object","additionalProperties":false,
                  "properties":{
                    "type":{"type":"string"},"title":{"type":"string"},"timeMinutes":{"type":"integer"},"kcal":{"type":"integer"},"proteinG":{"type":"number"},"carbsG":{"type":"number"},"fatG":{"type":"number"},"preparation":{"type":"string"},
                    "ingredients":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"properties":{"name":{"type":"string"},"quantity":{"type":"number"},"unit":{"type":"string"},"displayDose":{"type":"string"},"weightState":{"type":"string"},"nutritionConfidence":{"type":"string"},"category":{"type":"string"}},"required":["name","quantity","unit","displayDose","weightState","nutritionConfidence","category"]}}
                  },
                  "required":["type","title","timeMinutes","kcal","proteinG","carbsG","fatG","preparation","ingredients"]
                }}
              },
              "required":["dateEpochDay","totalKcal","proteinG","carbsG","fatG","hydrationNote","supplements","meals"]
            }},
            "agentValidation":{"type":"object","additionalProperties":false,"properties":{"valid":{"type":"boolean"},"notes":{"type":"string"}},"required":["valid","notes"]}
          },
          "required":["weekStartEpochDay","days","agentValidation"]
        }
        """.trimIndent()
    ).toString()

    data class AgentValidation(val valid: Boolean, val notes: String)
    data class Response(val weekStartEpochDay: Long, val days: List<GeneratedDay>, val agentValidation: AgentValidation)
    data class GeneratedDay(
        val dateEpochDay: Long, val totalKcal: Int, val proteinG: Float, val carbsG: Float, val fatG: Float,
        val meals: List<GeneratedMeal>, val supplements: List<GeneratedSupplement>, val hydrationNote: String,
    )
    data class GeneratedSupplement(
        val kind: String, val name: String, val dose: Float, val unit: String, val timeMinutes: Int,
        val kcal: Int, val proteinG: Float, val carbsG: Float, val fatG: Float, val notes: String,
    )
    data class GeneratedMeal(
        val type: String, val title: String, val timeMinutes: Int, val kcal: Int, val proteinG: Float,
        val carbsG: Float, val fatG: Float, val preparation: String, val ingredients: List<GeneratedIngredient>,
    )
    data class GeneratedIngredient(
        val name: String, val quantity: Float, val unit: String, val displayDose: String,
        val weightState: String, val nutritionConfidence: String, val category: String,
    )

    fun parse(jsonText: String): Response {
        val root = JSONObject(jsonText)
        val daysJson = root.getJSONArray("days")
        val days = buildList {
            for (i in 0 until daysJson.length()) {
                val day = daysJson.getJSONObject(i)
                val supplementsJson = day.getJSONArray("supplements")
                val supplements = buildList {
                    for (s in 0 until supplementsJson.length()) {
                        val item = supplementsJson.getJSONObject(s)
                        add(GeneratedSupplement(
                            kind = item.getString("kind").trim(), name = item.getString("name").trim(),
                            dose = item.getDouble("dose").toFloat(), unit = item.getString("unit").trim(),
                            timeMinutes = item.getInt("timeMinutes"), kcal = item.getInt("kcal"),
                            proteinG = item.getDouble("proteinG").toFloat(), carbsG = item.getDouble("carbsG").toFloat(),
                            fatG = item.getDouble("fatG").toFloat(), notes = item.getString("notes").trim(),
                        ))
                    }
                }
                val mealsJson = day.getJSONArray("meals")
                val meals = buildList {
                    for (m in 0 until mealsJson.length()) {
                        val meal = mealsJson.getJSONObject(m)
                        val ingredientsJson = meal.getJSONArray("ingredients")
                        val ingredients = buildList {
                            for (j in 0 until ingredientsJson.length()) {
                                val ingredient = ingredientsJson.getJSONObject(j)
                                add(GeneratedIngredient(
                                    ingredient.getString("name").trim(), ingredient.getDouble("quantity").toFloat(),
                                    ingredient.getString("unit").trim(), ingredient.getString("displayDose").trim(),
                                    ingredient.getString("weightState").trim(), ingredient.getString("nutritionConfidence").trim(),
                                    ingredient.getString("category").trim(),
                                ))
                            }
                        }
                        add(GeneratedMeal(
                            meal.getString("type").trim(), meal.getString("title").trim(), meal.getInt("timeMinutes"), meal.getInt("kcal"),
                            meal.getDouble("proteinG").toFloat(), meal.getDouble("carbsG").toFloat(), meal.getDouble("fatG").toFloat(),
                            meal.getString("preparation").trim(), ingredients,
                        ))
                    }
                }
                add(GeneratedDay(
                    day.getLong("dateEpochDay"), day.getInt("totalKcal"), day.getDouble("proteinG").toFloat(),
                    day.getDouble("carbsG").toFloat(), day.getDouble("fatG").toFloat(), meals, supplements,
                    day.getString("hydrationNote").trim(),
                ))
            }
        }
        val agent = root.getJSONObject("agentValidation")
        return Response(root.getLong("weekStartEpochDay"), days, AgentValidation(agent.getBoolean("valid"), agent.getString("notes")))
    }

    fun validateBusiness(
        response: Response,
        expectedWeekStart: LocalDate,
        targets: NutritionBusinessValidator.Targets,
        sportsMode: SportsNutritionClassifier.Mode,
    ): Result<Unit> = runCatching {
        require(response.weekStartEpochDay == expectedWeekStart.toEpochDay()) { "WEEK_START_MISMATCH" }
        require(response.days.size == 7) { "WEEK_MUST_HAVE_7_DAYS" }
        val expectedDates = (0L..6L).map { expectedWeekStart.plusDays(it).toEpochDay() }.toSet()
        require(response.days.map { it.dateEpochDay }.toSet() == expectedDates) { "WEEK_DATES_INVALID" }

        response.days.forEach { day ->
            require(day.meals.isNotEmpty()) { "DAY_WITHOUT_MEALS" }
            val appValidation = NutritionBusinessValidator.validate(targets, NutritionBusinessValidator.Actuals(day.totalKcal.toDouble(), day.proteinG.toDouble(), day.carbsG.toDouble(), day.fatG.toDouble()))
            require(appValidation.valid) { "TARGET_TOLERANCE_EXCEEDED" }

            day.supplements.forEach { supplement ->
                require(supplement.kind in setOf("PROTEIN_POWDER", "CREATINE")) { "SUPPLEMENT_NOT_ALLOWED" }
                require(supplement.dose > 0f && supplement.unit.isNotBlank() && supplement.timeMinutes in 0..1439) { "SUPPLEMENT_DOSE_INVALID" }
                require(supplement.kcal >= 0 && supplement.proteinG >= 0f && supplement.carbsG >= 0f && supplement.fatG >= 0f) { "SUPPLEMENT_MACROS_INVALID" }
                if (supplement.kind == "CREATINE") {
                    require(sportsMode == SportsNutritionClassifier.Mode.SPORT) { "CREATINE_REQUIRES_SPORT_MODE" }
                    require(supplement.kcal == 0 && supplement.proteinG == 0f && supplement.carbsG == 0f && supplement.fatG == 0f) { "CREATINE_HAS_NUTRITION_MACROS" }
                }
            }

            val actualKcal = day.meals.sumOf { it.kcal } + day.supplements.sumOf { it.kcal }
            val actualProtein = day.meals.sumOf { it.proteinG.toDouble() } + day.supplements.sumOf { it.proteinG.toDouble() }
            val actualCarbs = day.meals.sumOf { it.carbsG.toDouble() } + day.supplements.sumOf { it.carbsG.toDouble() }
            val actualFat = day.meals.sumOf { it.fatG.toDouble() } + day.supplements.sumOf { it.fatG.toDouble() }
            val consistency = NutritionBusinessValidator.validate(
                NutritionBusinessValidator.Targets(day.totalKcal.toDouble(), day.proteinG.toDouble(), day.carbsG.toDouble(), day.fatG.toDouble()),
                NutritionBusinessValidator.Actuals(actualKcal.toDouble(), actualProtein, actualCarbs, actualFat),
            )
            require(consistency.valid) { "DAY_TOTALS_INCONSISTENT" }

            day.meals.forEach { meal ->
                require(meal.type.isNotBlank() && meal.title.isNotBlank() && meal.timeMinutes in 0..1439) { "MEAL_INVALID" }
                require(meal.kcal >= 0 && meal.proteinG >= 0f && meal.carbsG >= 0f && meal.fatG >= 0f) { "MEAL_MACROS_INVALID" }
                meal.ingredients.forEach { ingredient ->
                    require(ingredient.name.isNotBlank() && ingredient.quantity > 0f && ingredient.quantity.isFinite()) { "INGREDIENT_INVALID" }
                    require(ingredient.unit.isNotBlank() && ingredient.displayDose.isNotBlank()) { "INGREDIENT_DOSE_MISSING" }
                }
            }
        }
    }
}
