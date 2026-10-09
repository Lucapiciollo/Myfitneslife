package com.myfitai.app.domain.food

import java.util.Locale

/** Explicit association between an alternative's ingredient, numeric quantity and weighing state. */
object MealAlternativeIngredientDisplay {
    fun exactQuantity(ingredient: MealAlternativeContract.Ingredient): String {
        val number = String.format(Locale.ITALIAN, "%.2f", ingredient.quantity)
            .trimEnd('0')
            .trimEnd(',')
        return listOf(number, ingredient.unit.trim()).filter(String::isNotBlank).joinToString(" ")
    }

    fun practicalDose(ingredient: MealAlternativeContract.Ingredient): String {
        val exact = exactQuantity(ingredient)
        val display = ingredient.displayDose.trim()
        return when {
            display.isBlank() -> exact
            display.equals(exact, ignoreCase = true) -> exact
            else -> "$exact · dose pratica: $display"
        }
    }

    /** Keep unknown provider states visible instead of silently assuming raw/cooked. */
    fun weightState(ingredient: MealAlternativeContract.Ingredient): String {
        val state = ingredient.weightState.trim()
        if (state.isBlank() || state == "?") return "stato del peso non specificato"
        return when (state.uppercase(Locale.ROOT)) {
            "RAW" -> "peso a crudo"
            "COOKED" -> "peso cotto"
            "DRY" -> "peso secco"
            "DRAINED" -> "peso sgocciolato"
            "AS_SOLD" -> "peso come venduto"
            else -> "stato peso: $state"
        }
    }
}
