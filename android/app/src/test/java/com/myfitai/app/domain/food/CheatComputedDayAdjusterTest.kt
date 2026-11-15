package com.myfitai.app.domain.food

import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.toDraft
import com.myfitai.app.domain.food.CheatComputedDayAdjuster.FallbackReason
import com.myfitai.app.domain.food.CheatComputedDayAdjuster.Outcome
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.catalog
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.standardMeals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Sgarro su giorni di piani calcolati (MACRO L5b): fixture sintetici sul catalogo reale, nessun dato personale. */
class CheatComputedDayAdjusterTest {
    private val builder = FoodCatalogPlanBuilder(catalog)
    private val adjuster = CheatComputedDayAdjuster(catalog)
    private val dayEpoch = FoodCatalogPlanFixtures.day.toEpochDay()

    private fun ingredient(foodId: String, grams: Double, order: Int): FoodIngredient {
        val g = builder.generatedIngredient(catalog.byId(foodId)!!, grams)
        return FoodIngredient(
            id = 0, mealId = 0, name = g.name, quantity = g.quantity, unit = g.unit, displayDose = g.displayDose,
            weightState = g.weightState, nutritionConfidence = g.nutritionConfidence, category = g.category, sortOrder = order,
            foodId = g.foodId, grams = g.grams, kcal = g.kcal, proteinG = g.proteinG, carbsG = g.carbsG, fatG = g.fatG,
            fiberG = g.fiberG, sodiumMg = g.sodiumMg,
        )
    }

    private fun meal(
        spec: FoodCatalogPlanFixtures.MealSpec,
        order: Int,
        source: String = FoodMeal.NUTRITION_SOURCE_COMPUTED,
        extra: List<Pair<String, Double>> = emptyList(),
    ): FoodMeal {
        val items = spec.items.map { it.first to it.second.toDouble() } + extra
        val ingredients = items.mapIndexed { i, (id, g) -> ingredient(id, g, i) }
        return FoodMeal(
            id = order.toLong() + 1, dayId = 1, sortOrder = order, type = spec.type, title = spec.title, timeMinutes = spec.time,
            kcal = Math.round(ingredients.sumOf { it.kcal!!.toDouble() }).toInt(),
            proteinG = ingredients.sumOf { it.proteinG!!.toDouble() }.toFloat(),
            carbsG = ingredients.sumOf { it.carbsG!!.toDouble() }.toFloat(),
            fatG = ingredients.sumOf { it.fatG!!.toDouble() }.toFloat(),
            preparation = spec.preparation, ingredients = ingredients, nutritionSource = source,
        )
    }

    private val supplement = FoodSupplement("PROTEIN", "Proteine in polvere", 25f, "g", 600, 100, 20f, 2f, 1f, null)

    private fun day(meals: List<FoodMeal>, supplements: List<FoodSupplement> = emptyList()) = FoodPlanDay(
        id = 1, dateEpochDay = dayEpoch,
        totalKcal = meals.sumOf { it.kcal ?: 0 } + supplements.sumOf { it.kcal },
        proteinG = meals.sumOf { (it.proteinG ?: 0f).toDouble() }.toFloat(),
        carbsG = meals.sumOf { (it.carbsG ?: 0f).toDouble() }.toFloat(),
        fatG = meals.sumOf { (it.fatG ?: 0f).toDouble() }.toFloat(),
        meals = meals, supplements = supplements,
        targetKcal = 2000, targetProteinG = 130f, targetCarbsG = 230f, targetFatG = 65f, baseTargetKcal = 2000,
    )

    private fun computedMeals(source: String = FoodMeal.NUTRITION_SOURCE_COMPUTED) =
        standardMeals.mapIndexed { i, spec -> meal(spec, i, source) }

    private fun computed(outcome: Outcome) = outcome as? Outcome.Computed ?: error("atteso Computed, ottenuto $outcome")

    /** Totali pasto = somma snapshot, nutritionSource COMPUTED e dosi presenti, per ogni pasto modificabile. */
    private fun assertCoherent(meals: List<MealDraft>, indices: Iterable<Int>) {
        for (i in indices) {
            val m = meals[i]
            assertEquals(FoodMeal.NUTRITION_SOURCE_COMPUTED, m.nutritionSource)
            assertTrue(m.ingredients.all { it.foodId != null && it.grams != null && !it.displayDose.isNullOrBlank() })
            val kcal = m.ingredients.sumOf { it.kcal!!.toDouble() }
            assertTrue("pasto $i kcal ${m.kcal} vs $kcal", abs(m.kcal!! - kcal) <= 0.5 + 1e-6)
            assertTrue(abs(m.proteinG!! - m.ingredients.sumOf { it.proteinG!!.toDouble() }) <= 0.05 + 1e-6)
            assertTrue(abs(m.carbsG!! - m.ingredients.sumOf { it.carbsG!!.toDouble() }) <= 0.05 + 1e-6)
            assertTrue(abs(m.fatG!! - m.ingredients.sumOf { it.fatG!!.toDouble() }) <= 0.05 + 1e-6)
        }
    }

    @Test
    fun computedFutureDay_repairsGramsWithinThreePercentAndKeepsSnapshotCoherent() {
        val source = day(computedMeals(), listOf(supplement))
        val before = source.copy()
        val outcome = computed(adjuster.adjust(source, source.meals.indices.toSet(), 1700, 1700.0 / 2000))

        assertTrue("scarto ${outcome.kcalDeviation}", abs(outcome.kcalDeviation) <= 0.03)
        assertEquals(source.meals.size, outcome.meals.size)
        assertCoherent(outcome.meals, source.meals.indices)
        // Totali del giorno = somma dei pasti + integratori, e kcal dello snapshot entro il 3% del nuovo target.
        val snapshotKcal = outcome.meals.sumOf { m -> m.ingredients.sumOf { it.kcal!!.toDouble() } } + supplement.kcal
        assertTrue("snapshot $snapshotKcal", abs(snapshotKcal - 1700) / 1700 <= 0.03 + 1e-6)
        // Limiti DEFAULT per voce (0,6x-1,5x) e per pasto (+-40%).
        source.meals.forEachIndexed { mi, m ->
            m.ingredients.forEachIndexed { ii, ing ->
                val g = outcome.meals[mi].ingredients[ii].grams!!
                assertTrue("g=$g base=${ing.grams}", g >= 0.6f * ing.grams!! - 0.06f && g <= 1.5f * ing.grams!! + 0.06f)
            }
            assertTrue(abs(outcome.meals[mi].kcal!! - m.kcal!!) <= 0.4 * m.kcal!! + 1)
        }
        assertTrue(outcome.meals.flatMap { it.ingredients }.zip(source.meals.flatMap { it.ingredients }).any { (n, o) -> n.grams != o.grams })
        assertEquals("la versione precedente non viene modificata", before, source)
    }

    @Test
    fun consumedMealsAndFixedItemsStayUnchanged() {
        val meals = standardMeals.mapIndexed { i, spec -> meal(spec, i, extra = if (i == 1) listOf("it.sale" to 2.0) else emptyList()) }
        val source = day(meals, listOf(supplement))
        val adjustable = setOf(2, 3)
        val target = 1800
        val outcome = computed(adjuster.adjust(source, adjustable, target, target / 2000.0))

        assertEquals(source.meals[0].toDraft(), outcome.meals[0])
        assertEquals(source.meals[1].toDraft(), outcome.meals[1])
        assertTrue(abs(outcome.kcalDeviation) <= 0.03)
        assertCoherent(outcome.meals, adjustable)
        val persisted = outcome.meals.sumOf { (it.kcal ?: 0).toDouble() } + supplement.kcal
        assertTrue(abs(persisted - target) / target <= 0.03 + 1e-6)

        // Una voce non scalabile in un pasto modificabile resta identica.
        val withSalt = day(listOf(meal(standardMeals[0], 0), meal(standardMeals[1], 1, extra = listOf("it.sale" to 2.0))))
        val saltOutcome = computed(adjuster.adjust(withSalt, setOf(0, 1), 1500, 0.8))
        val saltOld = withSalt.meals[1].ingredients.single { it.foodId == "it.sale" }
        val saltNew = saltOutcome.meals[1].ingredients.single { it.foodId == "it.sale" }
        assertEquals(saltOld.grams, saltNew.grams)
        assertEquals(saltOld.toDraft(), saltNew)
    }

    @Test
    fun infeasibleDay_fallsBackAndWideLimitsAreUsedOnlyWhenDefaultIsInfeasible() {
        val source = day(computedMeals())
        val impossible = adjuster.adjust(source, source.meals.indices.toSet(), 300, 0.15)
        assertEquals(Outcome.Fallback(FallbackReason.INFEASIBLE), impossible)

        // Raggiungibile solo con WIDE (0,5x-2,0x, +-60%): DEFAULT non scende sotto ~1.134 kcal.
        val wideTarget = 1050
        val wide = adjuster.adjust(source, source.meals.indices.toSet(), wideTarget, wideTarget / 2000.0)
        val result = computed(wide)
        assertTrue(result.usedWideLimits)
        assertTrue(abs(result.kcalDeviation) <= 0.03)
        assertCoherent(result.meals, source.meals.indices)

        // Con un target raggiungibile con DEFAULT, WIDE non si usa.
        assertTrue(!computed(adjuster.adjust(source, source.meals.indices.toSet(), 1700, 0.85)).usedWideLimits)
    }

    @Test
    fun declaredAndMixedDaysFallBackToHistoricBehaviour() {
        val declared = day(computedMeals(FoodMeal.NUTRITION_SOURCE_DECLARED))
        assertEquals(Outcome.Fallback(FallbackReason.DECLARED), adjuster.adjust(declared, declared.meals.indices.toSet(), 1700, 0.85))

        // Piano misto: pasti futuri in parte COMPUTED e in parte DECLARED -> giorno dichiarato.
        val mixedMeals = standardMeals.mapIndexed { i, spec -> meal(spec, i, if (i == 3) FoodMeal.NUTRITION_SOURCE_DECLARED else FoodMeal.NUTRITION_SOURCE_COMPUTED) }
        val mixed = day(mixedMeals)
        assertEquals(Outcome.Fallback(FallbackReason.MIXED_OR_UNRESOLVED), adjuster.adjust(mixed, mixed.meals.indices.toSet(), 1700, 0.85))

        // Pasto passato DECLARED con futuri tutti COMPUTED: il giorno e' calcolato, il passato entra come quota fissa.
        val pastDeclared = day(standardMeals.mapIndexed { i, spec -> meal(spec, i, if (i == 0) FoodMeal.NUTRITION_SOURCE_DECLARED else FoodMeal.NUTRITION_SOURCE_COMPUTED) })
        val outcome = computed(adjuster.adjust(pastDeclared, setOf(1, 2, 3), 1800, 0.9))
        assertEquals(pastDeclared.meals[0].toDraft(), outcome.meals[0])
        assertEquals(FoodMeal.NUTRITION_SOURCE_DECLARED, outcome.meals[0].nutritionSource)
        assertTrue(abs(outcome.kcalDeviation) <= 0.03)
        assertCoherent(outcome.meals, setOf(1, 2, 3))
    }

    @Test
    fun unresolvedIngredient_treatsDayAsDeclared() {
        val base = computedMeals()
        val broken = base[3].copy(ingredients = base[3].ingredients.map { it.copy(foodId = "it.non_esiste") })
        val source = day(base.dropLast(1) + broken)
        assertEquals(Outcome.Fallback(FallbackReason.MIXED_OR_UNRESOLVED), adjuster.adjust(source, source.meals.indices.toSet(), 1700, 0.85))
        val noSnapshot = base[3].copy(ingredients = base[3].ingredients.map { it.copy(kcal = null) })
        assertEquals(Outcome.Fallback(FallbackReason.MIXED_OR_UNRESOLVED), adjuster.adjust(day(base.dropLast(1) + noSnapshot), setOf(3), 1700, 0.85))
    }

    @Test
    fun resultIsDeterministicAndTitlesAndTimesArePreserved() {
        val source = day(computedMeals())
        val a = computed(adjuster.adjust(source, source.meals.indices.toSet(), 1750, 0.875))
        val b = computed(adjuster.adjust(source, source.meals.indices.toSet(), 1750, 0.875))
        assertEquals(a, b)
        source.meals.forEachIndexed { i, m ->
            assertEquals(m.title, a.meals[i].title)
            assertEquals(m.timeMinutes, a.meals[i].timeMinutes)
            assertEquals(m.preparation, a.meals[i].preparation)
            assertEquals(m.ingredients.map { it.foodId }, a.meals[i].ingredients.map { it.foodId })
        }
        assertNotEquals(source.meals.map { it.toDraft() }, a.meals)
    }
}
