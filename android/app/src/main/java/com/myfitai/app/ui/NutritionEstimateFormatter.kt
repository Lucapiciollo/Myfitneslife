package com.myfitai.app.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object NutritionEstimateFormatter {
    fun formatEstimatedKcal(value: Number?): String = formatEstimatedNutrition(value, "kcal", 0)

    fun formatEstimatedMacro(value: Number?, unit: String): String = formatEstimatedNutrition(value, unit, 1)

    fun formatEstimatedNutrition(value: Number?, unit: String): String =
        formatEstimatedNutrition(value, unit, if (unit == "kcal") 0 else 1)

    private fun formatEstimatedNutrition(value: Number?, unit: String, maxFractionDigits: Int): String {
        if (value == null) return "Dati non disponibili"
        val symbols = DecimalFormatSymbols(Locale.ITALIAN)
        val pattern = if (maxFractionDigits == 0) "#,##0" else "#,##0.#"
        val number = DecimalFormat(pattern, symbols).apply {
            isGroupingUsed = true
            maximumFractionDigits = maxFractionDigits
            minimumFractionDigits = 0
        }.format(value.toDouble())
        return "≈ $number $unit"
    }
}
