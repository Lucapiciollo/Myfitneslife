package com.myfitai.app.domain.shopping

import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.FoodPlanVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListEngineTest {

    @Test
    fun `sums compatible gram and kilogram quantities`() {
        val result = ShoppingListEngine.aggregate(snapshot(
            ingredient("Riso basmati", 500f, "g", "secco"),
            ingredient("riso   basmati", 1f, "kg", "secco"),
        ))

        assertEquals(1, result.size)
        assertEquals(1500.0, result.single().quantity, 0.001)
        assertEquals("g", result.single().unit)
        assertEquals("1,5 kg", result.single().displayQuantity())
    }

    @Test
    fun `does not merge different weight states`() {
        val result = ShoppingListEngine.aggregate(snapshot(
            ingredient("Pollo", 200f, "g", "crudo"),
            ingredient("Pollo", 200f, "g", "cotto"),
        ))

        assertEquals(2, result.size)
        assertTrue(result.mapNotNull { it.weightState }.containsAll(listOf("crudo", "cotto")))
    }

    @Test
    fun `ignores invalid non positive quantities`() {
        val result = ShoppingListEngine.aggregate(snapshot(
            ingredient("Mela", 0f, "pz", null),
            ingredient("Banana", 2f, "pz", null),
        ))
        assertEquals(listOf("Banana"), result.map { it.name })
    }

    private fun ingredient(name: String, quantity: Float, unit: String, weightState: String?) = FoodIngredient(
        id = 0,
        mealId = 0,
        name = name,
        quantity = quantity,
        unit = unit,
        displayDose = null,
        weightState = weightState,
        nutritionConfidence = "HIGH",
        category = "Cereali",
        sortOrder = 0,
    )

    private fun snapshot(vararg ingredients: FoodIngredient): FoodPlanSnapshot = FoodPlanSnapshot(
        planId = 1,
        profileId = 1,
        weekStartEpochDay = 1,
        version = FoodPlanVersion(
            id = 1,
            versionNumber = 1,
            createdAtEpochMillis = 1,
            source = "TEST",
            reason = null,
            targetKcal = 2000,
            targetProteinG = 150f,
            targetCarbsG = 200f,
            targetFatG = 70f,
            days = listOf(
                FoodPlanDay(
                    id = 1,
                    dateEpochDay = 1,
                    totalKcal = 2000,
                    proteinG = 150f,
                    carbsG = 200f,
                    fatG = 70f,
                    meals = listOf(
                        FoodMeal(
                            id = 1,
                            dayId = 1,
                            sortOrder = 0,
                            type = "Pranzo",
                            title = "Test",
                            timeMinutes = 720,
                            kcal = 500,
                            proteinG = 30f,
                            carbsG = 50f,
                            fatG = 10f,
                            preparation = null,
                            ingredients = ingredients.toList(),
                        )
                    ),
                )
            ),
        ),
    )
}
