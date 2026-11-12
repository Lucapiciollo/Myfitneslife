package com.myfitai.app.ui

import com.myfitai.app.domain.display.DisplayNumberFormatter

object NutritionEstimateFormatter {
    fun formatEstimatedKcal(value: Number?): String = formatEstimatedNutrition(value, "kcal", 0)

    fun formatEstimatedMacro(value: Number?, unit: String): String = formatEstimatedNutrition(value, unit, 1)

    fun formatEstimatedNutrition(value: Number?, unit: String): String =
        formatEstimatedNutrition(value, unit, if (unit == "kcal") 0 else 1)

    private fun formatEstimatedNutrition(value: Number?, unit: String, maxFractionDigits: Int): String {
        if (value == null) return "Dati non disponibili"
        // Same rounding and separators as Home and the PDF export (DisplayNumberFormatter).
        val number = when (value) {
            is Float -> DisplayNumberFormatter.decimal(value, maxFractionDigits)
            else -> DisplayNumberFormatter.decimal(value.toDouble(), maxFractionDigits)
        }
        return "\u2248 $number $unit"
    }
}
