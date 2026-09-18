package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import kotlin.math.abs
import kotlin.math.max

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
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("available", available); put("valid", valid); put("validatorVersion", validatorVersion); put("derivedTotals", derivedTotals)
        put("issues", org.json.JSONArray().apply { issues.forEach { put(it.toExportJson()) } })
        put("warnings", org.json.JSONArray().apply { warnings.forEach { put(it.toExportJson()) } })
    }
}

object NutritionIntegrityValidator {
    const val VERSION = "nutrition-integrity-v1"
    private const val KCAL_TOLERANCE_PERCENT = 0.10
    private const val KCAL_MIN_TOLERANCE = 50.0

    fun validate(response: NutritionPlanContract.Response, targets: NutritionBusinessValidator.Targets, mealsPerDay: Int): AppNutritionValidation {
        val issues = mutableListOf<NutritionValidationIssue>()
        response.days.forEach { day ->
            val dayValidation = NutritionBusinessValidator.validate(
                targets,
                NutritionBusinessValidator.Actuals(day.totalKcal.toDouble(), day.proteinG.toDouble(), day.carbsG.toDouble(), day.fatG.toDouble()),
            )
            if (!dayValidation.valid) issues += NutritionValidationIssue("TARGET_DAY_OUT_OF_RANGE", Severity.ERROR, "Il giorno non rispetta il target dinamico", day.dateEpochDay)
            day.meals.forEach { meal ->
                val expected = meal.proteinG * 4.0 + meal.carbsG * 4.0 + meal.fatG * 9.0
                val tolerance = max(KCAL_MIN_TOLERANCE, meal.kcal * KCAL_TOLERANCE_PERCENT)
                if (abs(expected - meal.kcal) > tolerance) issues += NutritionValidationIssue(
                    code = "MACRO_CALORIE_INCONSISTENCY", severity = Severity.ERROR,
                    message = "Le calorie dichiarate non sono coerenti con i macro", dayEpochDay = day.dateEpochDay,
                    mealTitle = meal.title, expected = expected, actual = meal.kcal.toDouble(), deviationPercent = abs(expected - meal.kcal) / meal.kcal * 100.0,
                )
                meal.ingredients.forEach { ingredient ->
                    if (ingredient.nutritionConfidence.equals("UNKNOWN", true) || ingredient.nutritionConfidence.isBlank()) issues += NutritionValidationIssue("INGREDIENT_NUTRITION_NOT_VERIFIABLE", Severity.WARNING, "Fonte nutrizionale ingrediente non verificabile", day.dateEpochDay, meal.title)
                }
            }
            val mealKcal = day.meals.sumOf { it.kcal } + day.supplements.sumOf { it.kcal }
            val mealProtein = day.meals.sumOf { it.proteinG.toDouble() } + day.supplements.sumOf { it.proteinG.toDouble() }
            val mealCarbs = day.meals.sumOf { it.carbsG.toDouble() } + day.supplements.sumOf { it.carbsG.toDouble() }
            val mealFat = day.meals.sumOf { it.fatG.toDouble() } + day.supplements.sumOf { it.fatG.toDouble() }
            if (mealKcal != day.totalKcal) issues += NutritionValidationIssue("DAY_MEALS_KCAL_MISMATCH", Severity.ERROR, "La somma dei pasti non coincide con il totale giornaliero", day.dateEpochDay, expected = mealKcal.toDouble(), actual = day.totalKcal.toDouble())
            if (abs(mealProtein - day.proteinG) > 0.1) issues += NutritionValidationIssue("DAY_MEALS_PROTEIN_MISMATCH", Severity.ERROR, "La somma delle proteine non coincide con il totale giornaliero", day.dateEpochDay)
            if (abs(mealCarbs - day.carbsG) > 0.1) issues += NutritionValidationIssue("DAY_MEALS_CARBS_MISMATCH", Severity.ERROR, "La somma dei carboidrati non coincide con il totale giornaliero", day.dateEpochDay)
            if (abs(mealFat - day.fatG) > 0.1) issues += NutritionValidationIssue("DAY_MEALS_FAT_MISMATCH", Severity.ERROR, "La somma dei grassi non coincide con il totale giornaliero", day.dateEpochDay)
        }
        return AppNutritionValidation(true, issues.none { it.severity == Severity.ERROR }, VERSION, issues.filter { it.severity == Severity.ERROR }, issues.filter { it.severity != Severity.ERROR })
    }

    private fun org.json.JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: org.json.JSONObject.NULL) }
}

private fun NutritionValidationIssue.toExportJson() = org.json.JSONObject().apply {
    put("code", code); put("severity", severity.name); put("message", message)
    putNullable("dayEpochDay", dayEpochDay); putNullable("mealTitle", mealTitle); putNullable("expected", expected); putNullable("actual", actual); putNullable("deviationPercent", deviationPercent)
}

private fun org.json.JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: org.json.JSONObject.NULL) }
