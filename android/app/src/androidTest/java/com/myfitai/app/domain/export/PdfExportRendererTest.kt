package com.myfitai.app.domain.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfExportRendererTest {
    @Test
    fun sixMeals_areAllKeptByPdfPagination() {
        val meals = (1L..6L).map { id -> meal(id) }

        val pages = PdfExportRenderer.paginateMeals(meals)

        assertEquals(meals.map(FoodMeal::id), pages.flatten().map(FoodMeal::id))
        assertEquals(6, pages.sumOf { it.size })
    }

    @Test
    fun longContent_movesWholeMealsToContinuationPages() {
        val longTitle = "Piatto completo con una descrizione volutamente lunga che deve andare a capo senza essere tagliata"
        val meals = (1L..6L).map { id -> meal(id, longTitle) }

        val pages = PdfExportRenderer.paginateMeals(meals, availableHeight = 180f)

        assertTrue(pages.size > 1)
        assertEquals(meals.map(FoodMeal::id), pages.flatten().map(FoodMeal::id))
    }

    private fun meal(id: Long, title: String = "Pasto completo $id") = FoodMeal(
        id = id,
        dayId = 1L,
        sortOrder = id.toInt(),
        type = "Pasto $id",
        title = title,
        timeMinutes = 420 + id.toInt() * 120,
        kcal = 350,
        proteinG = 30f,
        carbsG = 40f,
        fatG = 10f,
        preparation = null,
        ingredients = listOf(
            FoodIngredient(
                id = id,
                mealId = id,
                name = "Ingrediente principale con descrizione completa",
                quantity = 100f,
                unit = "g",
                displayDose = "100 g",
                weightState = null,
                nutritionConfidence = null,
                category = "Test",
                sortOrder = 0,
            )
        ),
    )
}
