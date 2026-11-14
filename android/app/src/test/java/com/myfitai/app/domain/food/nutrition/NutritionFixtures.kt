package com.myfitai.app.domain.food.nutrition

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dati sintetici per i test del nucleo di calcolo. I valori per 100 g sono tipici di tabelle pubbliche
 * (USDA SR Legacy, vedi docs/SPIKE_S1_RISULTATI.md). Nessun dato personale.
 */
internal object NutritionFixtures {
    val FOODS: Map<String, Per100> = linkedMapOf(
        "olio_evo" to Per100(884.0, 0.0, 0.0, 100.0, 0.0, 2.0, 0.0),
        "gallette_riso" to Per100(387.0, 8.2, 81.5, 2.8, 4.2, 26.0, 5.8),
        "melanzane" to Per100(25.0, 0.98, 5.88, 0.18, 3.0, 2.0, 92.3),
        "mela" to Per100(52.0, 0.26, 13.81, 0.17, 2.4, 1.0, 85.56),
        "banana" to Per100(89.0, 1.09, 22.84, 0.33, 2.6, 1.0, 74.91),
        "riso_basmati" to Per100(365.0, 7.13, 79.95, 0.66, 1.3, 5.0, 11.62),
        "riso_integrale" to Per100(367.0, 7.54, 76.25, 3.2, 3.6, 5.0, 11.8),
        "petto_pollo" to Per100(120.0, 22.5, 0.0, 2.62, 0.0, 45.0, 73.9),
        "fiocchi_avena" to Per100(379.0, 13.15, 67.7, 6.52, 10.1, 6.0, 10.84),
        "uovo" to Per100(143.0, 12.56, 0.72, 9.51, 0.0, 142.0, 76.15),
        "yogurt_greco_0" to Per100(59.0, 10.19, 3.6, 0.39, 0.0, 36.0, 85.1),
        "yogurt_magro" to Per100(56.0, 5.73, 7.68, 0.18, 0.0, 77.0, 85.23),
        "yogurt_soia" to Per100(88.0, 2.35, 17.65, 1.18, 0.6, 15.0, 77.59),
        "merluzzo" to Per100(82.0, 17.81, 0.0, 0.67, 0.0, 54.0, 81.22),
        "pane" to Per100(265.0, 9.0, 49.0, 3.2, 2.7, 491.0, 36.0),
        "ricotta" to Per100(174.0, 11.3, 3.0, 13.0, 0.0, 84.0, 75.0),
        "proteine_polvere" to Per100(380.0, 80.0, 8.0, 5.0, 0.0, 300.0, 4.0),
    )

    fun per100(id: String): Per100 = FOODS.getValue(id)

    fun ing(
        id: String,
        grams: Double,
        step: Double = IngredientInput.practicalStep(grams),
        minG: Double? = null,
        maxG: Double? = null,
        scalable: Boolean = true,
        locked: Boolean = false,
    ) = IngredientInput(id, per100(id), grams, minG, maxG, step, scalable, null, locked)

    class SyntheticDay(
        val meals: List<RepairMeal>,
        val targets: DayTargets,
        val feasible: Boolean,
        val exactMacros: Boolean,
    )

    private val POOL = FOODS.keys.filter { it != "olio_evo" && it != "proteine_polvere" }

    /**
     * Giorno sintetico deterministico per [seed]. Se [feasible] il target kcal è quello di una "verità"
     * (grammi dentro i limiti di default e dentro ±40% per pasto) quindi raggiungibile per costruzione;
     * se [exactMacros] anche i target P/C/F sono quelli della verità, altrimenti sono perturbati.
     * Se non [feasible] il target kcal è fuori dall'intervallo raggiungibile (2,5x o 0,3x delle kcal iniziali).
     */
    fun syntheticDay(seed: Long, feasible: Boolean = true, exactMacros: Boolean = true): SyntheticDay {
        val rnd = Random(seed)
        val mealCount = 3 + rnd.nextInt(4)
        val consumedFirst = rnd.nextInt(5) == 0
        val meals = ArrayList<RepairMeal>()
        for (m in 0 until mealCount) {
            val n = 2 + rnd.nextInt(3)
            val items = ArrayList<IngredientInput>()
            val used = HashSet<String>()
            repeat(n) {
                var id = POOL[rnd.nextInt(POOL.size)]
                while (!used.add(id)) id = POOL[rnd.nextInt(POOL.size)]
                val g = 20.0 + 5.0 * rnd.nextInt(40) // 20..215
                items += ing(id, g, minG = if (rnd.nextInt(3) == 0) 0.4 * g else null, maxG = if (rnd.nextInt(3) == 0) 3.0 * g else null)
            }
            if (rnd.nextInt(3) == 0) items += ing("olio_evo", 5.0 + 5.0 * rnd.nextInt(3))
            if (rnd.nextInt(6) == 0) items += ing("proteine_polvere", 25.0 + 5.0 * rnd.nextInt(3), scalable = false)
            if (rnd.nextInt(8) == 0) items[0] = items[0].copy(locked = true)
            meals += RepairMeal("m$m", items, consumed = consumedFirst && m == 0)
        }
        // Verità raggiungibile: fattori in [0,65-1,4], pasto entro [0,62-1,38] delle sue kcal.
        var truth = NutrientTotals.ZERO
        for (meal in meals) {
            val adj = meal.ingredients.map { it.isAdjustable && !meal.consumed }
            val factors = DoubleArray(meal.ingredients.size) { if (adj[it]) 0.65 + 0.75 * rnd.nextDouble() else 1.0 }
            val k0 = NutritionCalculator.meal(meal.ingredients).kcal
            repeat(12) {
                val k = meal.ingredients.indices.sumOf { i -> meal.ingredients[i].per100.kcal * meal.ingredients[i].grams * factors[i] / 100.0 }
                if (k0 > 0.0 && (k < 0.62 * k0 || k > 1.38 * k0)) {
                    for (i in factors.indices) if (adj[i]) factors[i] = 1.0 + (factors[i] - 1.0) * 0.5
                }
            }
            for (i in meal.ingredients.indices) {
                truth += NutritionCalculator.ingredient(meal.ingredients[i].per100, meal.ingredients[i].grams * factors[i])
            }
        }
        val k0 = NutritionCalculator.day(meals.map { it.ingredients }).kcal
        fun noise() = 0.85 + 0.3 * rnd.nextDouble()
        val targets = when {
            !feasible -> {
                val kcal = if (rnd.nextBoolean()) 2.5 * k0 else 0.3 * k0
                DayTargets(kcal, truth.proteinG, truth.carbsG, truth.fatG)
            }
            exactMacros -> DayTargets(truth.kcal, truth.proteinG, truth.carbsG, truth.fatG)
            else -> DayTargets(truth.kcal, truth.proteinG * noise(), truth.carbsG * noise(), truth.fatG * noise())
        }
        return SyntheticDay(meals, targets, feasible, exactMacros)
    }

    /** Verifica i vincoli duri sul risultato; ritorna l'elenco delle violazioni (vuoto = ok). */
    fun violations(meals: List<RepairMeal>, result: RepairResult, limits: RepairLimits, eps: Double = 1e-6): List<String> {
        val out = ArrayList<String>()
        if (result.mealGrams.size != meals.size) return listOf("numero pasti diverso")
        for ((mi, meal) in meals.withIndex()) {
            val grams = result.mealGrams[mi]
            if (grams.size != meal.ingredients.size) {
                out += "pasto $mi: numero voci diverso"
                continue
            }
            val k0 = NutritionCalculator.meal(meal.ingredients).kcal
            var k1 = 0.0
            for ((ii, ing) in meal.ingredients.withIndex()) {
                val g = grams[ii]
                k1 += ing.per100.kcal * g / 100.0
                if (meal.consumed || !ing.isAdjustable) {
                    if (abs(g - ing.grams) > 1e-9) out += "pasto $mi voce $ii: bloccata ma cambiata ($g vs ${ing.grams})"
                } else {
                    val lo = max(limits.lowFactor * ing.grams, ing.minG ?: 0.0)
                    val hi = min(limits.highFactor * ing.grams, ing.maxG ?: Double.MAX_VALUE)
                    if (g < lo - eps || g > hi + eps) out += "pasto $mi voce $ii: $g fuori [$lo, $hi]"
                }
            }
            if (!meal.consumed && abs(k1 - k0) > limits.mealKcalFraction * k0 + eps) out += "pasto $mi: kcal $k1 oltre ±${limits.mealKcalFraction} di $k0"
        }
        return out
    }
}
