package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            recent = listOf(meal("pasta al pomodoro")),
        )
        assertEquals(listOf("Pasta al pomodoro"), report.repeatedFromRecent)
        assertEquals(listOf("Pasta al pomodoro"), report.repeatedInPeriod)
        assertEquals(setOf("VARIETY_REPEATS_RECENT", "VARIETY_REPEATS_IN_PLAN"), report.issues().map { it.code }.toSet())
        assertTrue(report.issues().all { it.severity == Severity.WARNING })
    }

    @Test
    fun sameMealOnTwoDaysIsOnlyAPlanRepetition_noTrainingVsRestWarningExists() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("Riso e pollo")), day(101, meal("Riso e pollo"), meal("Frittata"))),
            recent = emptyList(),
        )
        assertEquals(listOf("Riso e pollo"), report.repeatedInPeriod)
        assertFalse(report.notice()!!.contains("allenamento"))
        assertFalse(report.issues().any { it.code == "VARIETY_TRAINING_SAME_AS_REST" })
        assertEquals(setOf("VARIETY_REPEATS_IN_PLAN"), report.issues().map { it.code }.toSet())
    }

    @Test
    fun oldAppValidationWithRemovedTrainingCodeStillParses() {
        val old = """{"available":true,"valid":true,"validatorVersion":"nutrition-integrity-v2","derivedTotals":true,"issues":[],"warnings":[{"code":"VARIETY_TRAINING_SAME_AS_REST","severity":"WARNING","message":"x","dayEpochDay":null,"mealTitle":null,"expected":null,"actual":null,"deviationPercent":null}]}"""
        val parsed = NutritionIntegrityValidator.parse(old)
        assertNotNull(parsed)
        assertEquals("VARIETY_TRAINING_SAME_AS_REST", parsed!!.warnings.single().code)
    }

    @Test
    fun sameIngredientSetCountsAsTheSameMealEvenWithAnotherTitle() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("Bowl del lunedì", "Riso", "Pollo", "Zucchine"))),
            recent = listOf(meal("Piatto unico", "zucchine", "RISO", "pollo")),
        )
        assertEquals(1, report.repeatedFromRecent.size)
    }

    @Test
    fun shortIngredientListsAreNotMatchedByIngredientsAlone() {
        val report = MealVariety.check(
            generated = listOf(day(100, meal("Yogurt con miele", "Yogurt", "Miele"))),
            recent = listOf(meal("Colazione leggera", "Yogurt", "Miele")),
        )
        assertFalse(report.hasRepetitions)
    }
}
