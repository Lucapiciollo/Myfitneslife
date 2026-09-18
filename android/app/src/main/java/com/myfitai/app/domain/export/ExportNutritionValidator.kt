package com.myfitai.app.domain.export

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.food.FoodPlanDay

object ExportNutritionValidator {
    fun appValidation(targetKcal: Int?, targetProtein: Float?, targetCarbs: Float?, targetFat: Float?, actualKcal: Int?, actualProtein: Float?, actualCarbs: Float?, actualFat: Float?): org.json.JSONObject? {
        if (targetKcal == null || targetProtein == null || targetCarbs == null || targetFat == null) return null
        val result = NutritionBusinessValidator.validate(
            NutritionBusinessValidator.Targets(targetKcal.toDouble(), targetProtein.toDouble(), targetCarbs.toDouble(), targetFat.toDouble()),
            NutritionBusinessValidator.Actuals(actualKcal?.toDouble() ?: 0.0, actualProtein?.toDouble() ?: 0.0, actualCarbs?.toDouble() ?: 0.0, actualFat?.toDouble() ?: 0.0),
        )
        return org.json.JSONObject().apply {
            put("tolerancePercent", NutritionBusinessValidator.DEFAULT_TOLERANCE * 100.0)
            put("valid", result.valid)
            putField("kcal", result.kcal); putField("protein", result.protein); putField("carbs", result.carbs); putField("fat", result.fat)
        }
    }

    fun appValidation(day: FoodPlanDay, targetKcal: Int?, targetProtein: Float?, targetCarbs: Float?, targetFat: Float?): org.json.JSONObject? {
        return appValidation(targetKcal, targetProtein, targetCarbs, targetFat, day.totalKcal, day.proteinG, day.carbsG, day.fatG)
    }

    private fun org.json.JSONObject.putField(name: String, field: NutritionBusinessValidator.FieldValidation) {
        put(name, org.json.JSONObject().apply {
            put("target", field.target); put("actual", field.actual); put("deltaPercent", field.deviationRatio * 100.0); put("valid", field.valid)
        })
    }
}
