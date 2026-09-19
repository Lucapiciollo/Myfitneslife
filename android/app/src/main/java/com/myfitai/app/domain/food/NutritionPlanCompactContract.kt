package com.myfitai.app.domain.food

import org.json.JSONObject

/**
 * Token-optimized transport for weekly nutrition plans.
 *
 * The provider still returns a tiny JSON envelope so the existing provider/schema pipeline remains
 * authoritative. The heavy weekly payload lives in a strict positional pipe protocol, removing
 * repeated JSON property names without weakening local business validation.
 */
object NutritionPlanCompactContract {
    const val SCHEMA_NAME = "myfitai_weekly_nutrition_pipe_v1"

    val schemaJson: String = JSONObject(
        """
        {
          "type":"object",
          "additionalProperties":false,
          "properties":{"data":{"type":"string"}},
          "required":["data"]
        }
        """.trimIndent()
    ).toString()

    const val PROTOCOL = """MFP1
W|weekStartEpochDay
D|dateEpochDay|totalKcal|proteinG|carbsG|fatG (followed by exactly MEALS_PER_DAY M records, as supplied in the user prompt)
M|type|title|timeMinutes|kcal|proteinG|carbsG|fatG|preparation
I|name|quantity|unit|displayDose|weightState|nutritionConfidence|category
S|kind|name|dose|unit|timeMinutes|kcal|proteinG|carbsG|fatG|notes
H|hydrationNote
V|1_or_0|notes"""

    fun parseEnvelope(jsonText: String, mealsPerDay: Int = NutritionPlanContract.REQUIRED_MEALS_PER_DAY): NutritionPlanContract.Response {
        val root = JSONObject(jsonText)
        return parsePayload(root.getString("data"), mealsPerDay)
    }

    fun parsePayload(payload: String, mealsPerDay: Int = NutritionPlanContract.REQUIRED_MEALS_PER_DAY): NutritionPlanContract.Response {
        var weekStart: Long? = null
        var validation: NutritionPlanContract.AgentValidation? = null
        val days = mutableListOf<NutritionPlanContract.GeneratedDay>()
        var currentDay: DayBuilder? = null
        var currentMeal: MealBuilder? = null

        fun flushMeal() {
            val meal = currentMeal ?: return
            require(meal.ingredients.isNotEmpty()) { "PIPE_MEAL_WITHOUT_INGREDIENTS" }
            currentDay?.meals?.add(meal.build()) ?: error("PIPE_MEAL_WITHOUT_DAY")
            currentMeal = null
        }

        fun flushDay() {
            flushMeal()
            val day = currentDay ?: return
            require(day.meals.size == mealsPerDay) { "PIPE_DAY_MUST_HAVE_${mealsPerDay}_MEALS" }
            days += day.build()
            currentDay = null
        }

        val lines = payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        require(lines.isNotEmpty()) { "PIPE_EMPTY" }
        require(lines.first() == "MFP1") { "PIPE_VERSION_INVALID" }

        lines.drop(1).forEach { line ->
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "W" -> {
                    require(parts.size >= 2 && weekStart == null && days.isEmpty() && currentDay == null) { "PIPE_W_INVALID" }
                    weekStart = parts[1].toLongStrict("PIPE_W_INVALID")
                }
                "D" -> {
                    require(parts.size >= 6 && weekStart != null) { "PIPE_D_INVALID" }
                    flushDay()
                    currentDay = DayBuilder(
                        dateEpochDay = parts[1].toLongStrict("PIPE_D_DATE_INVALID"),
                        totalKcal = parts[2].toIntStrict("PIPE_D_KCAL_INVALID"),
                        proteinG = parts[3].toFloatStrict("PIPE_D_P_INVALID"),
                        carbsG = parts[4].toFloatStrict("PIPE_D_C_INVALID"),
                        fatG = parts[5].toFloatStrict("PIPE_D_F_INVALID"),
                    )
                }
                "M" -> {
                    require(parts.size >= 9 && currentDay != null) { "PIPE_M_INVALID" }
                    flushMeal()
                    currentMeal = MealBuilder(
                        type = parts[1].requiredText("PIPE_M_TYPE_INVALID"),
                        title = parts[2].requiredText("PIPE_M_TITLE_INVALID"),
                        timeMinutes = parts[3].toIntStrict("PIPE_M_TIME_INVALID"),
                        kcal = parts[4].toIntStrict("PIPE_M_KCAL_INVALID"),
                        proteinG = parts[5].toFloatStrict("PIPE_M_P_INVALID"),
                        carbsG = parts[6].toFloatStrict("PIPE_M_C_INVALID"),
                        fatG = parts[7].toFloatStrict("PIPE_M_F_INVALID"),
                        preparation = parts.drop(8).joinToString("|").trim(),
                    )
                }
                "I" -> {
                    require(parts.size >= 8 && currentMeal != null) { "PIPE_I_INVALID" }
                    currentMeal!!.ingredients += NutritionPlanContract.GeneratedIngredient(
                        name = parts[1].requiredText("PIPE_I_NAME_INVALID"),
                        quantity = parts[2].toFloatStrict("PIPE_I_QTY_INVALID"),
                        unit = parts[3].requiredText("PIPE_I_UNIT_INVALID"),
                        displayDose = parts[4].requiredText("PIPE_I_DISPLAY_INVALID"),
                        weightState = parts[5].trim(),
                        nutritionConfidence = parts[6].trim(),
                        category = parts.drop(7).joinToString("|").trim(),
                    )
                }
                "S" -> {
                    require(parts.size >= 11 && currentDay != null) { "PIPE_S_INVALID" }
                    flushMeal()
                    currentDay!!.supplements += NutritionPlanContract.GeneratedSupplement(
                        kind = parts[1].requiredText("PIPE_S_KIND_INVALID"),
                        name = parts[2].requiredText("PIPE_S_NAME_INVALID"),
                        dose = parts[3].toFloatStrict("PIPE_S_DOSE_INVALID"),
                        unit = parts[4].requiredText("PIPE_S_UNIT_INVALID"),
                        timeMinutes = parts[5].toIntStrict("PIPE_S_TIME_INVALID"),
                        kcal = parts[6].toIntStrict("PIPE_S_KCAL_INVALID"),
                        proteinG = parts[7].toFloatStrict("PIPE_S_P_INVALID"),
                        carbsG = parts[8].toFloatStrict("PIPE_S_C_INVALID"),
                        fatG = parts[9].toFloatStrict("PIPE_S_F_INVALID"),
                        notes = parts.drop(10).joinToString("|").trim(),
                    )
                }
                "H" -> {
                    require(parts.size >= 2 && currentDay != null) { "PIPE_H_INVALID" }
                    flushMeal()
                    require(currentDay!!.hydrationNote == null) { "PIPE_H_DUPLICATE" }
                    currentDay!!.hydrationNote = parts.drop(1).joinToString("|").trim()
                }
                "V" -> {
                    require(parts.size >= 3 && weekStart != null) { "PIPE_V_INVALID" }
                    flushDay()
                    val valid = when (parts[1]) {
                        "1" -> true
                        "0" -> false
                        else -> error("PIPE_V_FLAG_INVALID")
                    }
                    validation = NutritionPlanContract.AgentValidation(valid, parts.drop(2).joinToString("|").trim())
                }
                else -> error("PIPE_RECORD_UNKNOWN")
            }
        }
        flushDay()

        require(weekStart != null) { "PIPE_WEEK_MISSING" }
        require(validation != null) { "PIPE_VALIDATION_MISSING" }
        return NutritionPlanContract.Response(weekStart!!, days, validation!!)
    }

    private data class DayBuilder(
        val dateEpochDay: Long,
        val totalKcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
        val meals: MutableList<NutritionPlanContract.GeneratedMeal> = mutableListOf(),
        val supplements: MutableList<NutritionPlanContract.GeneratedSupplement> = mutableListOf(),
        var hydrationNote: String? = null,
    ) {
        fun build(): NutritionPlanContract.GeneratedDay {
            val finalMeals = meals.toList()
            val finalSupplements = supplements.toList()
            // D totals are transport hints only. The app is authoritative and derives the
            // persisted/validated daily totals from the actual meal + caloric supplement records.
            val derivedKcal = finalMeals.sumOf { it.kcal } + finalSupplements.sumOf { it.kcal }
            val derivedProtein = (
                finalMeals.sumOf { it.proteinG.toDouble() } +
                    finalSupplements.sumOf { it.proteinG.toDouble() }
                ).toFloat()
            val derivedCarbs = (
                finalMeals.sumOf { it.carbsG.toDouble() } +
                    finalSupplements.sumOf { it.carbsG.toDouble() }
                ).toFloat()
            val derivedFat = (
                finalMeals.sumOf { it.fatG.toDouble() } +
                    finalSupplements.sumOf { it.fatG.toDouble() }
                ).toFloat()
            return NutritionPlanContract.GeneratedDay(
                dateEpochDay = dateEpochDay,
                totalKcal = derivedKcal,
                proteinG = derivedProtein,
                carbsG = derivedCarbs,
                fatG = derivedFat,
                meals = finalMeals,
                supplements = finalSupplements,
                hydrationNote = hydrationNote.orEmpty(),
            )
        }
    }

    private data class MealBuilder(
        val type: String,
        val title: String,
        val timeMinutes: Int,
        val kcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
        val preparation: String,
        val ingredients: MutableList<NutritionPlanContract.GeneratedIngredient> = mutableListOf(),
    ) {
        fun build() = NutritionPlanContract.GeneratedMeal(
            type = type,
            title = title,
            timeMinutes = timeMinutes,
            kcal = kcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            preparation = preparation,
            ingredients = ingredients.toList(),
        )
    }

    private fun String.requiredText(code: String): String = trim().also { require(it.isNotEmpty()) { code } }
    private fun String.toIntStrict(code: String): Int = toIntOrNull() ?: kotlin.error(code)
    private fun String.toLongStrict(code: String): Long = toLongOrNull() ?: kotlin.error(code)
    private fun String.toFloatStrict(code: String): Float = toFloatOrNull()?.takeIf { it.isFinite() } ?: kotlin.error(code)
}
