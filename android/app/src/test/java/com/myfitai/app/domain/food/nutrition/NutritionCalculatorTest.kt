package com.myfitai.app.domain.food.nutrition

import com.myfitai.app.domain.food.nutrition.NutritionFixtures.ing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionCalculatorTest {
    private val delta = 1e-9

    @Test
    fun ingredient_isPer100TimesGramsOver100() {
        // Riso basmati 80 g: kcal 365*0,8=292; P 7,13*0,8=5,704; C 79,95*0,8=63,96; F 0,66*0,8=0,528; fibre 1,04; Na 4.
        val t = NutritionCalculator.ingredient(NutritionFixtures.per100("riso_basmati"), 80.0)
        assertEquals(292.0, t.kcal, delta)
        assertEquals(5.704, t.proteinG, delta)
        assertEquals(63.96, t.carbsG, delta)
        assertEquals(0.528, t.fatG, delta)
        assertEquals(1.04, t.fiberG, delta)
        assertEquals(4.0, t.sodiumMg, delta)
    }

    @Test
    fun ml_areConvertedWithDensity() {
        // 10 ml di olio a 0,91 g/ml = 9,1 g -> 884*0,091 = 80,444 kcal, 9,1 g di grassi.
        val grams = NutritionCalculator.gramsFromMl(10.0, 0.91)
        assertEquals(9.1, grams, delta)
        val t = NutritionCalculator.ingredient(NutritionFixtures.per100("olio_evo"), grams)
        assertEquals(80.444, t.kcal, 1e-9)
        assertEquals(9.1, t.fatG, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ml_withNonPositiveDensity_isRejected() {
        NutritionCalculator.gramsFromMl(10.0, 0.0)
    }

    @Test
    fun meal_isSumOfIngredients_andDayIsSumOfMeals() {
        // Pasto: pollo 150 g (180 kcal, 33,75 P, 3,93 F) + riso 80 g (292 kcal) + olio 10 g (88,4 kcal, 10 F).
        val meal1 = listOf(ing("petto_pollo", 150.0), ing("riso_basmati", 80.0), ing("olio_evo", 10.0))
        val m1 = NutritionCalculator.meal(meal1)
        assertEquals(560.4, m1.kcal, 1e-9)
        assertEquals(33.75 + 5.704, m1.proteinG, 1e-9)
        assertEquals(63.96, m1.carbsG, 1e-9)
        assertEquals(3.93 + 0.528 + 10.0, m1.fatG, 1e-9)
        // Secondo pasto: mela 150 g (78 kcal).
        val meal2 = listOf(ing("mela", 150.0))
        val day = NutritionCalculator.day(listOf(meal1, meal2))
        assertEquals(638.4, day.kcal, 1e-9)
        assertEquals(m1.carbsG + 20.715, day.carbsG, 1e-9)
    }

    @Test
    fun emptyMealAndDay_areZero() {
        assertEquals(NutrientTotals.ZERO, NutritionCalculator.meal(emptyList()))
        assertEquals(NutrientTotals.ZERO, NutritionCalculator.day(emptyList()))
    }

    @Test
    fun mealWithGrams_usesGivenGramsNotInputGrams() {
        val meal = listOf(ing("riso_basmati", 80.0), ing("olio_evo", 10.0))
        val t = NutritionCalculator.mealWithGrams(meal, listOf(100.0, 5.0))
        assertEquals(365.0 + 44.2, t.kcal, 1e-9)
    }

    @Test
    fun rounding_isOnlyInPresentation_kcalIntegerMacrosOneDecimal() {
        val t = NutrientTotals(kcal = 80.444, proteinG = 5.704, carbsG = 63.96, fatG = 0.25, fiberG = 1.04, sodiumMg = 4.6)
        val r = t.rounded()
        assertEquals(80, r.kcal)
        assertEquals(5.7, r.proteinG, delta)
        assertEquals(64.0, r.carbsG, delta)
        assertEquals(0.3, r.fatG, delta) // half-up
        assertEquals(1.0, r.fiberG, delta)
        assertEquals(5, r.sodiumMg)
        // Il totale non arrotondato resta invariato.
        assertEquals(80.444, t.kcal, delta)
        assertSame(t, t)
    }

    @Test
    fun spikeS1_preRepairTotals_matchPythonReference() {
        // Giorno A dello spike S1 (stessi grammi e per100 di catalog_mini.json): pre = [1678.2, 119.7, 226.6, 32.1].
        val meals = listOf(
            listOf(ing("fiocchi_avena", 60.0), ing("yogurt_greco_0", 200.0), ing("banana", 100.0)),
            listOf(ing("petto_pollo", 150.0), ing("riso_basmati", 80.0), ing("olio_evo", 10.0)),
            listOf(ing("mela", 150.0), ing("yogurt_magro", 150.0)),
            listOf(ing("merluzzo", 200.0), ing("melanzane", 200.0), ing("riso_basmati", 60.0), ing("olio_evo", 10.0)),
        )
        val t = NutritionCalculator.day(meals)
        assertEquals(1678.2, t.kcal, 0.05)
        assertEquals(119.7, t.proteinG, 0.05)
        assertEquals(226.6, t.carbsG, 0.05)
        assertEquals(32.1, t.fatG, 0.05)
        assertTrue(t.fiberG > 0.0 && t.sodiumMg > 0.0)
    }
}
