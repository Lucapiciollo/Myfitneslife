package com.myfitai.app.domain.shopping

import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodPlanSnapshot
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Pure deterministic shopping-list aggregation. No AI is involved.
 * Quantities are summed only when units and weight state are compatible.
 */
object ShoppingListEngine {

    data class Item(
        val key: String,
        val name: String,
        val category: String,
        val quantity: Double,
        val unit: String,
        val weightState: String?,
        val sourceCount: Int,
    ) {
        fun displayQuantity(): String = when {
            unit == "g" && quantity >= 1000.0 -> format(quantity / 1000.0) + " kg"
            unit == "ml" && quantity >= 1000.0 -> format(quantity / 1000.0) + " l"
            else -> format(quantity) + " " + unit
        }
    }

    fun aggregate(snapshot: FoodPlanSnapshot): List<Item> {
        data class Acc(
            val name: String,
            val category: String,
            val unit: String,
            val weightState: String?,
            var quantity: Double,
            var count: Int,
        )

        val aggregated = linkedMapOf<String, Acc>()
        snapshot.version.days
            .sortedBy { it.dateEpochDay }
            .flatMap { it.meals.sortedBy { meal -> meal.sortOrder } }
            .flatMap { it.ingredients.sortedBy { ingredient -> ingredient.sortOrder } }
            .forEach { ingredient ->
                if (!ingredient.quantity.isFinite() || ingredient.quantity <= 0f) return@forEach
                val normalizedUnit = normalizeUnit(ingredient.unit)
                val converted = convertQuantity(ingredient.quantity.toDouble(), ingredient.unit, normalizedUnit)
                val category = normalizeCategory(ingredient.category)
                val weightState = ingredient.weightState?.trim()?.takeIf { it.isNotBlank() }
                val key = stableKey(ingredient.name, normalizedUnit, weightState)
                val existing = aggregated[key]
                if (existing == null) {
                    aggregated[key] = Acc(
                        name = canonicalName(ingredient.name),
                        category = category,
                        unit = normalizedUnit,
                        weightState = weightState,
                        quantity = converted,
                        count = 1,
                    )
                } else {
                    existing.quantity += converted
                    existing.count++
                }
            }

        return aggregated.map { (key, acc) ->
            Item(
                key = key,
                name = acc.name,
                category = acc.category,
                quantity = acc.quantity,
                unit = acc.unit,
                weightState = acc.weightState,
                sourceCount = acc.count,
            )
        }.sortedWith(compareBy<Item> { it.category.lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.unit })
    }

    fun stableKey(name: String, unit: String, weightState: String?): String = listOf(
        normalizeText(name),
        normalizeUnit(unit),
        normalizeText(weightState.orEmpty()),
    ).joinToString("|")

    private fun normalizeUnit(unit: String): String = when (normalizeText(unit)) {
        "grammo", "grammi", "gr", "g" -> "g"
        "chilogrammo", "chilogrammi", "chilo", "chili", "kg" -> "g"
        "millilitro", "millilitri", "ml" -> "ml"
        "litro", "litri", "l" -> "ml"
        "pezzo", "pezzi", "pz", "unita", "unità", "u" -> "pz"
        else -> unit.trim().lowercase(Locale.ROOT).ifBlank { "unità" }
    }

    private fun convertQuantity(quantity: Double, originalUnit: String, normalizedUnit: String): Double {
        val original = normalizeText(originalUnit)
        return when {
            normalizedUnit == "g" && original in setOf("kg", "chilogrammo", "chilogrammi", "chilo", "chili") -> quantity * 1000.0
            normalizedUnit == "ml" && original in setOf("l", "litro", "litri") -> quantity * 1000.0
            else -> quantity
        }
    }

    private fun normalizeCategory(value: String?): String {
        val clean = value?.trim().orEmpty()
        if (clean.isBlank()) return "Altro"
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    }

    private fun canonicalName(value: String): String {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    }

    private fun normalizeText(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun format(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (abs(rounded - rounded.toLong()) < 0.0001) rounded.toLong().toString()
        else String.format(Locale.ITALIAN, "%.1f", rounded)
    }
}