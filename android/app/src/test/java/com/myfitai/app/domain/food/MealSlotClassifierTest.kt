package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Test

class MealSlotClassifierTest {
    private fun slot(type: String?, time: Int? = null) = MealSlotClassifier.classify(type, time)

    @Test
    fun theSlotNamesOfThePlanMapToTheirOwnIcon() {
        assertEquals(MealSlot.BREAKFAST, slot("Colazione", 450))
        assertEquals(MealSlot.MORNING_SNACK, slot("Spuntino Mattina", 540))
        assertEquals(MealSlot.LUNCH, slot("Pranzo", 720))
        assertEquals(MealSlot.AFTERNOON_SNACK, slot("Spuntino Pomeriggio", 930))
        assertEquals(MealSlot.DINNER, slot("Cena", 1140))
        assertEquals(MealSlot.EVENING_SNACK, slot("Spuntino Sera", 1380))
    }

    @Test
    fun theNameWinsOverTheTime() {
        assertEquals("a late lunch is still lunch", MealSlot.LUNCH, slot("pranzo", 16 * 60))
        assertEquals(MealSlot.DINNER, slot("CENA", 19 * 60))
    }

    @Test
    fun englishAndCaseVariantsAreUnderstood() {
        assertEquals(MealSlot.BREAKFAST, slot("Breakfast"))
        assertEquals(MealSlot.LUNCH, slot(" lunch "))
        assertEquals(MealSlot.DINNER, slot("Dinner"))
        assertEquals(MealSlot.MORNING_SNACK, slot("Snack Morning"))
    }

    @Test
    fun workoutMealsAreRecognised() {
        assertEquals(MealSlot.WORKOUT, slot("Pre-workout", 1000))
        assertEquals(MealSlot.WORKOUT, slot("Post workout"))
        assertEquals(MealSlot.WORKOUT, slot("Spuntino pre allenamento", 1000))
    }

    @Test
    fun aGenericSnackFollowsTheTimeOfDay() {
        assertEquals(MealSlot.MORNING_SNACK, slot("Spuntino", 10 * 60))
        assertEquals(MealSlot.AFTERNOON_SNACK, slot("Spuntino", 16 * 60))
        assertEquals(MealSlot.EVENING_SNACK, slot("Spuntino", 21 * 60))
        assertEquals(MealSlot.AFTERNOON_SNACK, slot("Spuntino", null))
        assertEquals("Merenda is the afternoon snack", MealSlot.AFTERNOON_SNACK, slot("Merenda", 10 * 60))
    }

    @Test
    fun anUnknownNameFallsBackToTheTimeOfDay() {
        assertEquals(MealSlot.BREAKFAST, slot("Pasto", 8 * 60))
        assertEquals(MealSlot.LUNCH, slot("Pasto", 13 * 60))
        assertEquals(MealSlot.DINNER, slot("", 20 * 60))
        assertEquals(MealSlot.EVENING_SNACK, slot(null, 23 * 60))
        assertEquals(MealSlot.OTHER, slot("Pasto", null))
    }
}