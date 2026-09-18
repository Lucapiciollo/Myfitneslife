package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

data class NutritionValidationIssue(
    val code: String,
    val severity: Severity,
    val message: String,
    val dayEpochDay: Long? = null,
    val mealTitle: String? = null,
    val expected: Double? = null,
    val actual: Double? = null,
    val deviationPercent: Double? = null,
)

enum class Severity { INFO, WARNING, ERROR }

data class AppNutritionValidation(
    val available: Boolean,
    val valid: Boolean,
    val validatorVersion: String,
    val issues: List<NutritionValidationIssue>,
    val warnings: List<NutritionValidationIssue>,
    val derivedTotals: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("valid", valid)
        put("validatorVersion", validatorVersion)
        put("derivedTotals", derivedTotals)
        put("issues", JSONArray().apply { issues.forEach { put(it.toJson()) } })
        put("warnings", JSONArray().apply { warnings.forEach { put(it.toJson()) } })
    }
}

object NutritionIntegrityValidator {
    const val VERSION = "nutrition-integrity-v1"
    private const val KCAL_TOLERANCE_RATIO = 0.15
    private const val KCAL_MIN_TOLERANCE = 50.0
    private const val MACRO_TOLERANCE = 0.2

    fun validate(
        response: NutritionPlanContract.Response,
        targets: NutritionBusinessValidator.Targets,
        mealsPerDay: Int,
        dailyTargets: Map<Long, NutritionBusinessValidator.Targets> = emptyMap(),
    ): AppNutritionValidation {
        val errors = mutableListOf<NutritionValidationIssue>()
        val warnings = mutableListOf<NutritionValidationIssue>()
        response.days.forEach { day ->
            val target = dailyTargets[day.dateEpochDay] ?: targets
            val targetResult = NutritionBusinessValidator.validate(
                target,
                NutritionBusinessValidator.Actuals(day.totalKcal.toDouble(), day.proteinG.toDouble(), day.carbsG.toDouble(), day.fatG.toDouble()),
            )
            if (!targetResult.valid) errors += issue("TARGET_DAY_OUT_OF_RANGE", "Il giorno non rispetta il target dinamico", dayEpochDay = day.dateEpochDay)
            if (day.meals.size != mealsPerDay) errors += issue("MEAL_COUNT_INVALID", "Il numero di pasti non coincide con il profilo", dayEpochDay = day.dateEpochDay, expected = mealsPerDay.toDouble(), actual = day.meals.size.toDouble())

            day.meals.forEach { meal -> validateItem(meal.kcal, meal.proteinG, meal.carbsG, meal.fatG, day.dateEpochDay, meal.title, errors) }
            day.supplements.forEach { supplement -> validateItem(supplement.kcal, supplement.proteinG, supplement.carbsG, supplement.fatG, day.dateEpochDay, supplement.name, errors) }

            val mealKcal = day.meals.sumOf { it.kcal } + day.supplements.sumOf { it.kcal }
            val mealProtein = day.meals.sumOf { it.proteinG.toDouble() } + day.supplements.sumOf { it.proteinG.toDouble() }
            val mealCarbs = day.meals.sumOf { it.carbsG.toDouble() } + day.supplements.sumOf { it.carbsG.toDouble() }
            val mealFat = day.meals.sumOf { it.fatG.toDouble() } + day.supplements.sumOf { it.fatG.toDouble() }
            if (mealKcal != day.totalKcal) errors += issue("DAY_MEALS_KCAL_MISMATCH", "La somma dei pasti non coincide con il totale giornaliero", day.dateEpochDay, expected = mealKcal.toDouble(), actual = day.totalKcal.toDouble())
            if (abs(mealProtein - day.proteinG) > MACRO_TOLERANCE) errors += issue("DAY_MEALS_PROTEIN_MISMATCH", "La somma delle proteine non coincide con il totale giornaliero", day.dateEpochDay, expected = mealProtein, actual = day.proteinG.toDouble())
            if (abs(mealCarbs - day.carbsG) > MACRO_TOLERANCE) errors += issue("DAY_MEALS_CARBS_MISMATCH", "La somma dei carboidrati non coincide con il totale giornaliero", day.dateEpochDay, expected = mealCarbs, actual = day.carbsG.toDouble())
            if (abs(mealFat - day.fatG) > MACRO_TOLERANCE) errors += issue("DAY_MEALS_FAT_MISMATCH", "La somma dei grassi non coincide con il totale giornaliero", day.dateEpochDay, expected = mealFat, actual = day.fatG.toDouble())
            day.meals.flatMap { it.ingredients }.filter { it.nutritionConfidence.isBlank() || it.nutritionConfidence.equals("UNKNOWN", true) }.forEach {
                warnings += issue("INGREDIENT_NUTRITION_NOT_VERIFIABLE", "Fonte nutrizionale ingrediente non verificabile", day.dateEpochDay)
            }
        }
        return AppNutritionValidation(true, errors.isEmpty(), VERSION, errors, warnings)
    }

    private fun validateItem(kcal: Int, protein: Float, carbs: Float, fat: Float, day: Long, title: String, errors: MutableList<NutritionValidationIssue>) {
        val expected = protein * 4.0 + carbs * 4.0 + fat * 9.0
        val tolerance = max(KCAL_MIN_TOLERANCE, kcal * KCAL_TOLERANCE_RATIO)
        if (abs(expected - kcal) > tolerance) errors += issue("MACRO_CALORIE_INCONSISTENCY", "Le calorie dichiarate non sono coerenti con i macro", day, title, expected, kcal.toDouble(), if (kcal == 0) null else abs(expected - kcal) / kcal * 100.0)
    }

    private fun issue(code: String, message: String, dayEpochDay: Long? = null, mealTitle: String? = null, expected: Double? = null, actual: Double? = null, deviationPercent: Double? = null) = NutritionValidationIssue(code, Severity.ERROR, message, dayEpochDay, mealTitle, expected, actual, deviationPercent)
}

private fun NutritionValidationIssue.toJson() = JSONObject().apply {
    put("code", code); put("severity", severity.name); put("message", message)
    put("dayEpochDay", dayEpochDay ?: JSONObject.NULL); put("mealTitle", mealTitle ?: JSONObject.NULL)
    put("expected", expected ?: JSONObject.NULL); put("actual", actual ?: JSONObject.NULL); put("deviationPercent", deviationPercent ?: JSONObject.NULL)
}
