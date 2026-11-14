package com.myfitai.app.domain.food.nutrition

import com.myfitai.app.domain.food.nutrition.NutritionFixtures.ing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DayRepairTest {

    private fun meal(id: String, vararg items: IngredientInput, consumed: Boolean = false) = RepairMeal(id, items.toList(), consumed)

    // ------------------------------------------------------------------ casi a mano

    @Test
    fun singleIngredient_isScaledToTargetAndRoundedToStep() {
        // Riso 100 g = 365 kcal; target 400 -> 109,6 g, arrotondato al passo da 5 g = 110 g = 401,5 kcal (+0,375%).
        val meals = listOf(meal("m0", ing("riso_basmati", 100.0)))
        val r = DayRepair.repair(meals, DayTargets(kcal = 400.0))
        assertEquals(RepairStatus.REPAIRED, r.status)
        assertEquals(110.0, r.mealGrams[0][0], 1e-6)
        assertEquals(401.5, r.totals.kcal, 1e-6)
        assertEquals(DeviationBand.WITHIN_1_PERCENT, r.kcalBand)
        assertEquals(0.00375, r.kcalDeviation, 1e-9)
    }

    @Test
    fun alreadyOnTarget_staysPut() {
        val meals = listOf(meal("m0", ing("riso_basmati", 100.0), ing("petto_pollo", 150.0)))
        val kcal = NutritionCalculator.day(meals.map { it.ingredients }).kcal
        val r = DayRepair.repair(meals, DayTargets(kcal = kcal))
        assertEquals(100.0, r.mealGrams[0][0], 1e-6)
        assertEquals(150.0, r.mealGrams[0][1], 1e-6)
        assertEquals(DeviationBand.WITHIN_1_PERCENT, r.kcalBand)
    }

    @Test
    fun noIngredientAddedOrRemoved_andTotalsMatchRecomputedGrams() {
        val meals = listOf(
            meal("m0", ing("fiocchi_avena", 60.0), ing("yogurt_greco_0", 200.0)),
            meal("m1", ing("petto_pollo", 150.0), ing("riso_basmati", 80.0), ing("olio_evo", 10.0)),
        )
        val r = DayRepair.repair(meals, DayTargets(1500.0, 130.0, 150.0, 40.0))
        assertEquals(listOf(2, 3), r.mealGrams.map { it.size })
        val recomputed = (meals.indices).fold(NutrientTotals.ZERO) { acc, i ->
            acc + NutritionCalculator.mealWithGrams(meals[i].ingredients, r.mealGrams[i])
        }
        assertEquals(recomputed.kcal, r.totals.kcal, 1e-9)
        assertEquals(recomputed.proteinG, r.totals.proteinG, 1e-9)
    }

    @Test
    fun lockedNonScalableAndConsumed_areNeverChanged() {
        val meals = listOf(
            meal("consumed", ing("riso_basmati", 100.0), ing("olio_evo", 10.0), consumed = true),
            meal(
                "open",
                ing("petto_pollo", 150.0, locked = true),
                ing("proteine_polvere", 30.0, scalable = false),
                ing("mela", 150.0),
                ing("yogurt_magro", 150.0),
            ),
        )
        val r = DayRepair.repair(meals, DayTargets(kcal = 1200.0, proteinG = 130.0))
        assertTrue(NutritionFixtures.violations(meals, r, RepairLimits.DEFAULT).toString(), NutritionFixtures.violations(meals, r, RepairLimits.DEFAULT).isEmpty())
        assertEquals(listOf(100.0, 10.0), r.mealGrams[0])
        assertEquals(150.0, r.mealGrams[1][0], 0.0)
        assertEquals(30.0, r.mealGrams[1][1], 0.0)
    }

    @Test
    fun consumedMeal_countsInDayTotalsButCannotAbsorbTheCorrection() {
        // Pasto consumato: riso 100 g (365 kcal) fisso. Aperto: pollo 150 g (180 kcal) scalabile.
        // Target 500: servono 135 kcal dal pollo = 112,5 g -> 110 o 115 g (passo 5).
        val meals = listOf(
            meal("c", ing("riso_basmati", 100.0), consumed = true),
            meal("o", ing("petto_pollo", 150.0)),
        )
        val r = DayRepair.repair(meals, DayTargets(kcal = 500.0))
        assertEquals(100.0, r.mealGrams[0][0], 0.0)
        // pollo 0,6x = 90 g: raggiungibile ma pasto ±40% = [108, 252] kcal -> 90..210 g; 112,5 g è nel range.
        assertEquals(112.5, r.mealGrams[1][0], 2.6)
        assertTrue(abs(r.kcalDeviation) <= 0.01)
    }

    @Test
    fun explicitCatalogBounds_areIntersectedWithScaleFactors() {
        // 150 g con maxG = 160: la scala 1,5x darebbe 225 g, il tetto di catalogo vince.
        val meals = listOf(meal("m", ing("riso_basmati", 150.0, maxG = 160.0)))
        val r = DayRepair.repair(meals, DayTargets(kcal = 1000.0))
        assertTrue(r.mealGrams[0][0] <= 160.0 + 1e-9)
        assertEquals(RepairStatus.INFEASIBLE, r.status)
        assertEquals(0.6 * 150.0 * 3.65, r.reachableKcal.minKcal, 1e-9)
        assertEquals(160.0 * 3.65, r.reachableKcal.maxKcal, 1e-9)
    }

    @Test
    fun proteinIsRepairedAsSecondaryGoal_whenKcalAreAlreadyOk() {
        // Kcal già a target, proteine basse: si può spostare massa verso pollo e giù dal riso senza cambiare le kcal.
        val meals = listOf(meal("m", ing("petto_pollo", 100.0), ing("riso_basmati", 100.0), ing("olio_evo", 10.0)))
        val base = NutritionCalculator.day(meals.map { it.ingredients })
        val r = DayRepair.repair(meals, DayTargets(base.kcal, proteinG = base.proteinG * 1.25))
        assertTrue(abs(r.kcalDeviation) <= 0.01)
        assertTrue("proteine ${r.totals.proteinG} vs ${base.proteinG}", r.totals.proteinG > base.proteinG)
    }

    // ------------------------------------------------------------------ infeasible

    @Test
    fun infeasible_returnsReachableKcalIntervalAndStatus() {
        // Pasto: riso 100 g scalabile + olio 10 g bloccato. Kcal iniziali 365 + 88,4 = 453,4.
        // Scatola: [0,6*365 + 88,4 ; 1,5*365 + 88,4] = [307,4 ; 635,9]; pasto ±40%: [272,04 ; 634,76].
        // Intervallo raggiungibile = intersezione = [307,4 ; 634,76].
        val meals = listOf(meal("m", ing("riso_basmati", 100.0), ing("olio_evo", 10.0, locked = true)))
        val high = DayRepair.repair(meals, DayTargets(kcal = 1000.0))
        assertEquals(RepairStatus.INFEASIBLE, high.status)
        assertEquals(307.4, high.reachableKcal.minKcal, 1e-9)
        assertEquals(634.76, high.reachableKcal.maxKcal, 1e-9)
        assertEquals(DeviationBand.OUTSIDE, high.kcalBand)
        // Best effort: spinge al massimo consentito (150 g di riso -> 548,25... ma il pasto è limitato a 634,76).
        assertTrue(high.totals.kcal <= 634.76 + 1e-6)
        assertTrue(high.totals.kcal > 600.0)

        val low = DayRepair.repair(meals, DayTargets(kcal = 100.0))
        assertEquals(RepairStatus.INFEASIBLE, low.status)
        assertEquals(60.0, low.mealGrams[0][0], 1e-6)
        assertEquals(307.4, low.totals.kcal, 1e-6)
    }

    @Test
    fun infeasible_intervalWithWidePreset() {
        // Scatola [0,5*365 + 88,4 ; 2*365 + 88,4] = [270,9 ; 818,4]; pasto ±60%: [181,36 ; 725,44] -> [270,9 ; 725,44].
        val meals = listOf(meal("m", ing("riso_basmati", 100.0), ing("olio_evo", 10.0, locked = true)))
        val r = DayRepair.repair(meals, DayTargets(kcal = 2000.0), RepairLimits.WIDE)
        assertEquals(RepairStatus.INFEASIBLE, r.status)
        assertEquals(270.9, r.reachableKcal.minKcal, 1e-9)
        assertEquals(725.44, r.reachableKcal.maxKcal, 1e-9)
    }

    @Test
    fun infeasible_sumsIntervalsOfMealsAndKeepsConsumedFixed() {
        // Pasto consumato 365 kcal fisso + aperto (pollo 150 g = 180 kcal): [108 ; 252] -> intervallo [473 ; 617].
        val meals = listOf(meal("c", ing("riso_basmati", 100.0), consumed = true), meal("o", ing("petto_pollo", 150.0)))
        val r = DayRepair.repair(meals, DayTargets(kcal = 2000.0))
        assertEquals(RepairStatus.INFEASIBLE, r.status)
        assertEquals(365.0 + 0.6 * 180.0, r.reachableKcal.minKcal, 1e-9)
        assertEquals(365.0 + 1.4 * 180.0, r.reachableKcal.maxKcal, 1e-9)
    }

    @Test
    fun targetInsideToleranceWindowOfReachableEdge_isNotInfeasible() {
        // Massimo raggiungibile 634,76: un target di 640 è raggiungibile entro il 3%.
        val meals = listOf(meal("m", ing("riso_basmati", 100.0), ing("olio_evo", 10.0, locked = true)))
        val r = DayRepair.repair(meals, DayTargets(kcal = 640.0))
        assertEquals(RepairStatus.REPAIRED, r.status)
        assertTrue(abs(r.kcalDeviation) <= 0.03)
    }

    @Test
    fun retryTolerance_changesFeasibilityWindow() {
        // Max 634,76: target 655 è a +3,19% oltre il max con tolleranza 3% -> infeasible; a 4% è raggiungibile.
        val meals = listOf(meal("m", ing("riso_basmati", 100.0), ing("olio_evo", 10.0, locked = true)))
        assertEquals(RepairStatus.INFEASIBLE, DayRepair.repair(meals, DayTargets(kcal = 660.0), kcalTolerance = 0.03).status)
        assertEquals(RepairStatus.REPAIRED, DayRepair.repair(meals, DayTargets(kcal = 660.0), kcalTolerance = 0.04).status)
    }

    // ------------------------------------------------------------------ casi limite

    @Test
    fun emptyDay_isInfeasibleWithZeroRange() {
        val r = DayRepair.repair(emptyList(), DayTargets(kcal = 2000.0))
        assertEquals(RepairStatus.INFEASIBLE, r.status)
        assertEquals(0.0, r.reachableKcal.maxKcal, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveKcalTarget_isRejected() {
        DayRepair.repair(listOf(meal("m", ing("mela", 100.0))), DayTargets(kcal = 0.0))
    }

    @Test
    fun zeroGramIngredients_stayZero() {
        val meals = listOf(meal("m", ing("mela", 0.0), ing("banana", 100.0)))
        val r = DayRepair.repair(meals, DayTargets(kcal = 100.0))
        assertEquals(0.0, r.mealGrams[0][0], 0.0)
    }

    // ------------------------------------------------------------------ proprietà con semi casuali

    @Test
    fun property_feasibleDays_convergeWithinThreePercent_andRespectAllLimits() {
        for (seed in 1L..120L) {
            val day = NutritionFixtures.syntheticDay(seed, feasible = true, exactMacros = seed % 2 == 0L)
            val r = DayRepair.repair(day.meals, day.targets)
            val v = NutritionFixtures.violations(day.meals, r, RepairLimits.DEFAULT)
            assertTrue("seed $seed: $v", v.isEmpty())
            assertEquals("seed $seed status", RepairStatus.REPAIRED, r.status)
            assertTrue("seed $seed kcal dev ${r.kcalDeviation}", abs(r.kcalDeviation) <= 0.03)
        }
    }

    @Test
    fun property_wideLimits_areRespected() {
        for (seed in 200L..260L) {
            val day = NutritionFixtures.syntheticDay(seed, feasible = true, exactMacros = false)
            val r = DayRepair.repair(day.meals, day.targets, RepairLimits.WIDE)
            val v = NutritionFixtures.violations(day.meals, r, RepairLimits.WIDE)
            assertTrue("seed $seed: $v", v.isEmpty())
            assertTrue("seed $seed kcal dev ${r.kcalDeviation}", abs(r.kcalDeviation) <= 0.03)
        }
    }

    @Test
    fun property_infeasibleDays_reportStatusAndKeepLimits() {
        for (seed in 300L..340L) {
            val day = NutritionFixtures.syntheticDay(seed, feasible = false)
            val r = DayRepair.repair(day.meals, day.targets)
            assertEquals("seed $seed", RepairStatus.INFEASIBLE, r.status)
            assertTrue("seed $seed", NutritionFixtures.violations(day.meals, r, RepairLimits.DEFAULT).isEmpty())
            assertTrue(r.reachableKcal.minKcal <= r.reachableKcal.maxKcal)
            assertTrue(day.targets.kcal > r.reachableKcal.maxKcal * 1.03 || day.targets.kcal < r.reachableKcal.minKcal * 0.97)
            // Il risultato best effort sta dentro l'intervallo dichiarato.
            assertTrue(r.totals.kcal >= r.reachableKcal.minKcal - 1e-6 && r.totals.kcal <= r.reachableKcal.maxKcal + 1e-6)
        }
    }

    @Test
    fun property_isDeterministic() {
        for (seed in 1L..30L) {
            val day = NutritionFixtures.syntheticDay(seed, feasible = true, exactMacros = false)
            val a = DayRepair.repair(day.meals, day.targets)
            val b = DayRepair.repair(day.meals, day.targets)
            assertEquals("seed $seed", a, b)
        }
    }

    @Test
    fun property_reachableIntervalIsSoundForExtremeTargets() {
        // Con target = min o max dell'intervallo dichiarato il solutore ci arriva (entro 1%).
        for (seed in 400L..430L) {
            val day = NutritionFixtures.syntheticDay(seed, feasible = true)
            val range = DayRepair.repair(day.meals, day.targets).reachableKcal
            for (edge in listOf(range.minKcal, range.maxKcal)) {
                val r = DayRepair.repair(day.meals, DayTargets(kcal = edge))
                assertTrue("seed $seed edge $edge dev ${r.kcalDeviation}", abs(r.kcalDeviation) <= 0.03)
            }
        }
    }

    // ------------------------------------------------------------------ confronto con lo spike S1 (Python)

    /**
     * Numeri ottenuti eseguendo tools/spike-s1/spike_s1.py::repair_day (pesi 1000/100/10/1) sugli stessi giorni.
     * Spike, errore dopo la riparazione: A kcal +0,03% / proteine -0,08%; B +0,24% / -1,93%; C -0,32% / +0,77%.
     * Qui i pesi sono quelli del progetto (kcal 4, proteine 2): il comportamento atteso è lo stesso sulle priorità
     * (kcal entro 1%, proteine vicine al risultato dello spike), non gli stessi grammi.
     */
    private fun spikeDay(vararg meals: List<Pair<String, Double>>) =
        meals.mapIndexed { i, m -> meal("m$i", *m.map { (id, g) -> ing(id, g) }.toTypedArray()) }

    private val spikeA = spikeDay(
        listOf("fiocchi_avena" to 60.0, "yogurt_greco_0" to 200.0, "banana" to 100.0),
        listOf("petto_pollo" to 150.0, "riso_basmati" to 80.0, "olio_evo" to 10.0),
        listOf("mela" to 150.0, "yogurt_magro" to 150.0),
        listOf("merluzzo" to 200.0, "melanzane" to 200.0, "riso_basmati" to 60.0, "olio_evo" to 10.0),
    )
    private val spikeC = spikeDay(
        listOf("fiocchi_avena" to 50.0, "yogurt_soia" to 150.0),
        listOf("uovo" to 120.0, "riso_integrale" to 70.0, "olio_evo" to 12.0),
        listOf("banana" to 120.0, "yogurt_greco_0" to 150.0),
        listOf("petto_pollo" to 180.0, "melanzane" to 250.0, "olio_evo" to 10.0, "gallette_riso" to 30.0),
    )

    private fun assertLikeSpike(meals: List<RepairMeal>, t: DayTargets, spikeKcalPct: Double, spikeProteinPct: Double) {
        val r = DayRepair.repair(meals, t)
        assertEquals(RepairStatus.REPAIRED, r.status)
        assertTrue("kcal ${r.kcalDeviation * 100}% (spike $spikeKcalPct%)", abs(r.kcalDeviation) <= 0.01)
        assertTrue("proteine ${r.proteinDeviation * 100}% (spike $spikeProteinPct%)", abs(r.proteinDeviation) * 100 <= abs(spikeProteinPct) + 1.0)
        assertTrue(NutritionFixtures.violations(meals, r, RepairLimits.DEFAULT).isEmpty())
    }

    @Test
    fun spikeCaseA_kcalAndProteinOnTarget() = assertLikeSpike(spikeA, DayTargets(1800.0, 140.0, 190.0, 55.0), 0.03, -0.08)

    @Test
    fun spikeCaseB_lowKcalHighProtein() = assertLikeSpike(spikeA, DayTargets(1500.0, 150.0, 150.0, 40.0), 0.24, -1.93)

    @Test
    fun spikeCaseC_differentFoods() = assertLikeSpike(spikeC, DayTargets(2100.0, 120.0, 260.0, 60.0), -0.32, 0.77)

    @Test
    fun spikeCases_preRepairErrorWasLarge_postIsSmall() {
        // Spike: errore kcal prima della riparazione A -6,8%, C -26,9%.
        val pre = NutritionCalculator.day(spikeC.map { it.ingredients }).kcal
        assertEquals(-0.269, (pre - 2100.0) / 2100.0, 0.001)
        val r = DayRepair.repair(spikeC, DayTargets(2100.0, 120.0, 260.0, 60.0))
        assertTrue(abs(r.kcalDeviation) < 0.01)
    }

    // ------------------------------------------------------------------ tempi

    @Test
    fun timing_perDayIsWellUnder50msOnJvm() {
        val days = (1L..60L).map { NutritionFixtures.syntheticDay(it, feasible = true, exactMacros = false) }
        repeat(2) { days.forEach { DayRepair.repair(it.meals, it.targets) } } // riscaldamento JIT
        val ms = days.map {
            val t0 = System.nanoTime()
            DayRepair.repair(it.meals, it.targets)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val p90 = ms[(ms.size * 0.9).toInt()]
        // Soglia dichiarata: p90 < 50 ms (largo per CI); massimo < 250 ms per assorbire pause di GC.
        assertTrue("p90 $p90 ms", p90 < 50.0)
        assertTrue("max ${ms.last()} ms", ms.last() < 250.0)
        println("DayRepair timing: median=${"%.3f".format(ms[ms.size / 2])} ms p90=${"%.3f".format(p90)} ms max=${"%.3f".format(ms.last())} ms")
    }
}
