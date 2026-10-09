package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Test

class MealAlternativeIngredientDisplayTest {
    private fun ingredient(
        quantity: Float,
        unit: String,
        displayDose: String,
        weightState: String,
    ) = MealAlternativeContract.Ingredient(
        name = "Riso basmati",
        quantity = quantity,
        unit = unit,
        displayDose = displayDose,
        weightState = weightState,
        nutritionConfidence = "HIGH",
        category = "CEREALS",
    )

    @Test
    fun displaysExactAndPracticalDoseTogetherAndNamesWeightState() {
        val rice = ingredient(80f, "g", "80 g", "DRY")
        val oil = ingredient(4.5f, "g", "1 cucchiaino", "RAW")

        assertEquals("80 g", MealAlternativeIngredientDisplay.exactQuantity(rice))
        assertEquals("80 g", MealAlternativeIngredientDisplay.practicalDose(rice))
        assertEquals("peso secco", MealAlternativeIngredientDisplay.weightState(rice))
        assertEquals("4,5 g · dose pratica: 1 cucchiaino", MealAlternativeIngredientDisplay.practicalDose(oil))
        assertEquals("peso a crudo", MealAlternativeIngredientDisplay.weightState(oil))
    }

    @Test
    fun unknownWeightStateIsExplicitAndDisplayDoseNeverHidesNumericWeight() {
        val item = ingredient(125f, "ml", "mezzo bicchiere", "?")

        assertEquals("125 ml · dose pratica: mezzo bicchiere", MealAlternativeIngredientDisplay.practicalDose(item))
        assertEquals("stato del peso non specificato", MealAlternativeIngredientDisplay.weightState(item))
    }
}
