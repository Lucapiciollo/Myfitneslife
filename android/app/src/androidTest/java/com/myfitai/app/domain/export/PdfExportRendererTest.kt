package com.myfitai.app.domain.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.myfitai.app.R
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.FoodPlanVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfExportRendererTest {
    private val renderer = PdfExportRenderer(ApplicationProvider.getApplicationContext())

    @Test
    fun sixMeals_areAllKeptByPdfPagination() {
        val meals = (1L..6L).map { id -> meal(id) }

        val pages = renderer.paginateMeals(meals)

        assertEquals(meals.map(FoodMeal::id), pages.flatten().map(FoodMeal::id))
        assertEquals(6, pages.sumOf { it.size })
    }

    @Test
    fun longContent_movesWholeMealsToContinuationPages() {
        val longTitle = "Piatto completo con una descrizione volutamente lunga che deve andare a capo senza essere tagliata"
        val meals = (1L..6L).map { id -> meal(id, longTitle) }

        val pages = renderer.paginateMeals(meals, availableHeight = 180f)

        assertTrue(pages.size > 1)
        assertEquals(meals.map(FoodMeal::id), pages.flatten().map(FoodMeal::id))
    }

    @Test
    fun weeklyPdfExport_usesResourceBackedPaletteAndWritesNonEmptyDocument() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "pdf-palette-test-${System.nanoTime()}.pdf")
        try {
            val day = FoodPlanDay(
                id = 1L,
                dateEpochDay = 20_000L,
                totalKcal = 1800,
                proteinG = 120f,
                carbsG = 200f,
                fatG = 60f,
                meals = listOf(meal(1L)),
            )
            val snapshot = FoodPlanSnapshot(
                planId = 1L,
                profileId = 1L,
                weekStartEpochDay = 20_000L,
                version = FoodPlanVersion(
                    id = 1L,
                    versionNumber = 1,
                    createdAtEpochMillis = 1L,
                    source = "TEST",
                    reason = null,
                    targetKcal = 1800,
                    targetProteinG = 120f,
                    targetCarbsG = 200f,
                    targetFatG = 60f,
                    days = listOf(day),
                ),
            )
            PdfExportRenderer(context).writeWeeklyPlanReport(file, "Palette", snapshot, emptyList())
            assertTrue(file.exists())
            assertTrue("PDF export should not be empty", file.length() > 0L)
            assertTrue("PDF header should be written", file.inputStream().use { input ->
                ByteArray(5).also { input.read(it) }.decodeToString() == "%PDF-"
            })
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    pdf.openPage(0).use { page ->
                        assertEquals(context.resources.getInteger(R.integer.pdf_page_width_page_units), page.width)
                        assertEquals(context.resources.getInteger(R.integer.pdf_page_height_page_units), page.height)
                    }
                }
            }
        } finally {
            file.delete()
        }
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
