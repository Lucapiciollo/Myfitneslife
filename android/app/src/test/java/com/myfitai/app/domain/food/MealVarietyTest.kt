package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealVarietyTest {
    private fun meal(title: String, vararg ingredients: String, type: String = "Pranzo") =
        MealVariety.Meal(type, title, ingredients.toList())

    private fun day(date: Long, vararg meals: MealVariety.Meal) = MealVariety.DayMeals(date, meals.toList())

    @Test
    fun normalizationIgnoresCaseAccentsAndPunctuation() {
        assertEquals("pasta al pomodoro", MealVariety.normalize("  Pàsta   al Pomodoro! "))
    }

    @Test
    fun memoryKeepsNewestDistinctMealsAndCapsTheSize() {
        val history = (1L..40L).map { d -> day(d, meal("Pasto $d a"), meal("Pasto $d b")) } +
            day(41L, meal("pasto 40 a"))
        val memory = MealVariety.memory(history)

        assertEquals(MealVariety.MAX_MEMORY_MEALS, memory.size)
        assertEquals("pasto 40 a", memory.first().title)
        assertEquals("duplicates collapse", memory.map { MealVariety.normalize(it.title) }.toSet().size, memory.size)
    }

    @Test
    fun promptLinesAreCompactAndSafe() {
        val lines = MealVariety.promptLines(listOf(meal("Riso | pollo\nlimone", type = "Cena")))
        assertEquals(listOf("RM:Cena|Riso / pollo limone"), lines)
    }

    @Test
    fun aDistinctPlanHasNoNotice() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("A")), day(101, meal("B"))),
            trainingDays = setOf(101L),
            recent = listOf(meal("Z")),
        )
        assertFalse(report.hasRepetitions)
        assertNull(report.notice())
        assertTrue(report.issues().isEmpty())
    }

    @Test
    fun detectsMealsServedRecentlyAndRepeatedInThePlan() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("Pasta al pomodoro"), meal("Insalata")), day(101, meal("Pasta  al Pomodoro"))),
            trainingDays = emptySet(),
            recent = listOf(meal("pasta al pomodoro")),
        )
        assertEquals(listOf("Pasta al pomodoro"), report.repeatedFromRecent)
        assertEquals(listOf("Pasta al pomodoro"), report.repeatedInPeriod)
        assertTrue(report.trainingSameAsRest.isEmpty())
        assertEquals(setOf("VARIETY_REPEATS_RECENT", "VARIETY_REPEATS_IN_PLAN"), report.issues().map { it.code }.toSet())
        assertTrue(report.issues().all { it.severity == Severity.WARNING })
    }

    @Test
    fun detectsTrainingMealsEqualToRestMeals() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("Riso e pollo")), day(101, meal("Riso e pollo"), meal("Frittata"))),
            trainingDays = setOf(101L),
            recent = emptyList(),
        )
        assertEquals(listOf("Riso e pollo"), report.trainingSameAsRest)
        assertTrue(report.notice()!!.contains("uguali tra allenamento e riposo"))
    }

    @Test
    fun noTrainingDaysMeansNoTrainingComparison() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("A")), day(101, meal("A"))),
            trainingDays = emptySet(),
            recent = emptyList(),
        )
        assertTrue(report.trainingSameAsRest.isEmpty())
        assertEquals(listOf("A"), report.repeatedInPeriod)
    }

    @Test
    fun sameIngredientSetCountsAsTheSameMealEvenWithAnotherTitle() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("Bowl del lunedì", "Riso", "Pollo", "Zucchine"))),
            trainingDays = emptySet(),
            recent = listOf(meal("Piatto unico", "zucchine", "RISO", "pollo")),
        )
        assertEquals(1, report.repeatedFromRecent.size)
    }

    @Test
    fun shortIngredientListsAreNotMatchedByIngredientsAlone() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("Yogurt con miele", "Yogurt", "Miele"))),
            trainingDays = emptySet(),
            recent = listOf(meal("Colazione leggera", "Yogurt", "Miele")),
        )
        assertFalse(report.hasRepetitions)
    }
}
