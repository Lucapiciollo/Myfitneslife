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
D|dateEpochDay|totalKcal|proteinG|carbsG|fatG
M|type|title|timeMinutes|kcal|proteinG|carbsG|fatG|preparation
I|name|quantity|unit|displayDose|weightState|nutritionConfidence|category
S|kind|name|dose|unit|timeMinutes|kcal|proteinG|carbsG|fatG|notes
H|hydrationNote
V|1_or_0|notes"""

    fun parseEnvelope(jsonText: String): NutritionPlanContract.Response {
        val root = JSONObject(jsonText)
        return parsePayload(root.getString("data"))
    }

    fun parsePayload(payload: String): NutritionPlanContract.Response {
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
            require(day.meals.isNotEmpty()) { "PIPE_DAY_WITHOUT_MEALS" }
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
                    require(parts.size == 2 && weekStart == null && days.isEmpty() && currentDay == null) { "PIPE_W_INVALID" }
                    weekStart = parts[1].toLongStrict("PIPE_W_INVALID")
                }
                "D" -> {
                    require(parts.size == 6 && weekStart != null && validation == null) { "PIPE_D_INVALID" }
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
                    require(parts.size == 9 && currentDay != null && validation == null) { "PIPE_M_INVALID" }
                    flushMeal()
                    currentMeal = MealBuilder(
                        type = parts[1].requiredText("PIPE_M_TYPE_INVALID"),
                        title = parts[2].requiredText("PIPE_M_TITLE_INVALID"),
                        timeMinutes = parts[3].toIntStrict("PIPE_M_TIME_INVALID"),
                        kcal = parts[4].toIntStrict("PIPE_M_KCAL_INVALID"),
                        proteinG = parts[5].toFloatStrict("PIPE_M_P_INVALID"),
                        carbsG = parts[6].toFloatStrict("PIPE_M_C_INVALID"),
                        fatG = parts[7].toFloatStrict("PIPE_M_F_INVALID"),
                        preparation = parts[8].trim(),
                    )
                }
                "I" -> {
                    require(parts.size == 8 && currentMeal != null && validation == null) { "PIPE_I_INVALID" }
                    currentMeal!!.ingredients += NutritionPlanContract.GeneratedIngredient(
                        name = parts[1].requiredText("PIPE_I_NAME_INVALID"),
                        quantity = parts[2].toFloatStrict("PIPE_I_QTY_INVALID"),
                        unit = parts[3].requiredText("PIPE_I_UNIT_INVALID"),
                        displayDose = parts[4].requiredText("PIPE_I_DISPLAY_INVALID"),
                        weightState = parts[5].trim(),
                        nutritionConfidence = parts[6].trim(),
                        category = parts[7].trim(),
                    )
                }
                "S" -> {
                    require(parts.size == 11 && currentDay != null && validation == null) { "PIPE_S_INVALID" }
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
                        notes = parts[10].trim(),
                    )
                }
                "H" -> {
                    require(parts.size == 2 && currentDay != null && validation == null) { "PIPE_H_INVALID" }
                    flushMeal()
                    require(currentDay!!.hydrationNote == null) { "PIPE_H_DUPLICATE" }
                    currentDay!!.hydrationNote = parts[1].trim()
                }
                "V" -> {
                    require(parts.size == 3 && weekStart != null && validation == null) { "PIPE_V_INVALID" }
                    flushDay()
                    val valid = when (parts[1]) {
                        "1" -> true
                        "0" -> false
                        else -> error("PIPE_V_FLAG_INVALID")
                    }
                    validation = NutritionPlanContract.AgentValidation(valid, parts[2].trim())
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
        fun build() = NutritionPlanContract.GeneratedDay(
            dateEpochDay = dateEpochDay,
            totalKcal = totalKcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            meals = meals.toList(),
            supplements = supplements.toList(),
            hydrationNote = hydrationNote.orEmpty(),
        )
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

    private fun String.requiredText(error: String): String = trim().also { require(it.isNotEmpty()) { error } }
    private fun String.toIntStrict(error: String): Int = toIntOrNull() ?: error(error)
    private fun String.toLongStrict(error: String): Long = toLongOrNull() ?: error(error)
    private fun String.toFloatStrict(error: String): Float = toFloatOrNull()?.takeIf { it.isFinite() } ?: error(error)
}
