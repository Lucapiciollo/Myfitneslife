package com.myfitai.app.domain.food

import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.toDraft
import com.myfitai.app.domain.food.catalog.FoodCatalog
import com.myfitai.app.domain.food.catalog.FoodEntry
import com.myfitai.app.domain.food.nutrition.DayRepair
import com.myfitai.app.domain.food.nutrition.DayTargets
import com.myfitai.app.domain.food.nutrition.RepairLimits
import com.myfitai.app.domain.food.nutrition.RepairMeal
import com.myfitai.app.domain.food.nutrition.RepairStatus
import com.myfitai.app.domain.food.nutrition.ValidationPolicy
import kotlin.math.abs

/**
 * Sgarro su un giorno di un piano CALCOLATO (MACRO L5b): invece di scalare i totali dei pasti futuri (che
 * lascerebbe lo snapshot degli ingredienti incoerente) ripara i grammi delle voci scalabili con lo stesso
 * [DayRepair] del piano, verso il nuovo target del giorno.
 *
 * Il giorno e' trattato come CALCOLATO solo se TUTTI i pasti modificabili (futuri) sono COMPUTED e ogni loro
 * ingrediente e' risolvibile nel catalogo con snapshot completo; altrimenti (pasti dichiarati, piano misto,
 * voci sconosciute) l'esito e' [Outcome.Fallback] e il chiamante usa il comportamento storico invariato.
 * Pasti gia' passati: se COMPUTED e risolvibili entrano nel solver come consumati (bloccati), altrimenti e come
 * gli integratori entrano come quota fissa (kcal/macro persistiti) sottratta dal target.
 *
 * Limiti DEFAULT; WIDE solo se DEFAULT e' INFEASIBLE. Se anche WIDE e' INFEASIBLE, o le kcal persistite del giorno
 * non sono entro +-3% del target, nessun risultato parziale: [Outcome.Fallback].
 */
internal class CheatComputedDayAdjuster(private val catalog: FoodCatalog) {

    enum class FallbackReason {
        /** Nessun pasto modificabile e' COMPUTED: piano dichiarato, comportamento storico (nessun avviso). */
        DECLARED,

        /** Piano misto o ingredienti non risolvibili/senza snapshot: giorno trattato come dichiarato. */
        MIXED_OR_UNRESOLVED,

        /** Target non valido (kcal fisse oltre il nuovo target). */
        NO_TARGET,

        /** Ne' DEFAULT ne' WIDE possono raggiungere il target entro la tolleranza. */
        INFEASIBLE,

        /** Il solver e' arrivato fuori dalla tolleranza kcal sui valori persistiti. */
        OUT_OF_TOLERANCE,
    }

    sealed interface Outcome {
        /** [meals] ha la stessa lunghezza e ordine di `day.meals`; i pasti bloccati sono copie invariate. */
        data class Computed(val meals: List<MealDraft>, val usedWideLimits: Boolean, val kcalDeviation: Double) : Outcome

        data class Fallback(val reason: FallbackReason) : Outcome
    }

    private val builder = FoodCatalogPlanBuilder(catalog)

    private class Resolved(val ingredient: FoodIngredient, val food: FoodEntry)

    private fun resolve(meal: FoodMeal): List<Resolved>? {
        if (meal.nutritionSource != FoodMeal.NUTRITION_SOURCE_COMPUTED || meal.ingredients.isEmpty()) return null
        return meal.ingredients.map { ing ->
            val food = ing.foodId?.let(catalog::byId) ?: return null
            if ((ing.grams ?: 0f) <= 0f || ing.kcal == null || ing.proteinG == null || ing.carbsG == null || ing.fatG == null) return null
            Resolved(ing, food)
        }
    }

    /**
     * @param adjustable indici in `day.meals` dei pasti ancora modificabili (futuri).
     * @param targetKcal nuovo target kcal del giorno dopo lo sgarro (kcal totali del giorno, integratori inclusi).
     * @param macroScale rapporto nuovo/vecchio target applicato ai target macro del giorno.
     */
    fun adjust(day: FoodPlanDay, adjustable: Set<Int>, targetKcal: Int, macroScale: Double): Outcome {
        val futureResolved = adjustable.sorted().map { i -> day.meals[i] to resolve(day.meals[i]) }
        if (futureResolved.none { it.first.nutritionSource == FoodMeal.NUTRITION_SOURCE_COMPUTED }) return Outcome.Fallback(FallbackReason.DECLARED)
        if (futureResolved.any { it.second == null }) return Outcome.Fallback(FallbackReason.MIXED_OR_UNRESOLVED)

        // Pasti del solver nell'ordine del giorno; quota fissa per integratori e pasti passati non risolvibili.
        val repairMeals = mutableListOf<RepairMeal>()
        val repairIndex = mutableListOf<Int>()
        val resolvedByMeal = mutableMapOf<Int, List<Resolved>>()
        var fixedKcal = day.supplements.sumOf { it.kcal.toDouble() }
        var fixedP = day.supplements.sumOf { it.proteinG.toDouble() }
        var fixedC = day.supplements.sumOf { it.carbsG.toDouble() }
        var fixedF = day.supplements.sumOf { it.fatG.toDouble() }
        day.meals.forEachIndexed { index, meal ->
            val consumed = index !in adjustable
            val resolved = resolve(meal)
            if (resolved != null) {
                resolvedByMeal[index] = resolved
                repairIndex += index
                repairMeals += RepairMeal("M$index", resolved.map { builder.ingredientInput(it.food, it.ingredient.grams!!.toDouble()) }, consumed)
            } else {
                fixedKcal += meal.kcal ?: 0
                fixedP += meal.proteinG ?: 0f
                fixedC += meal.carbsG ?: 0f
                fixedF += meal.fatG ?: 0f
            }
        }

        val repairKcal = targetKcal - fixedKcal
        if (repairKcal <= 0.0) return Outcome.Fallback(FallbackReason.NO_TARGET)
        fun macro(target: Float?, fixed: Double) = ((target ?: 0f) * macroScale - fixed).coerceAtLeast(0.0)
        val targets = DayTargets(repairKcal, macro(day.targetProteinG, fixedP), macro(day.targetCarbsG, fixedC), macro(day.targetFatG, fixedF))
        // +-3% del target del GIORNO espresso sul target ridotto della quota fissa.
        val tolerance = ValidationPolicy.FIRST_ATTEMPT_TOLERANCE * targetKcal / repairKcal

        var wide = false
        var result = DayRepair.repair(repairMeals, targets, RepairLimits.DEFAULT, tolerance)
        if (result.status == RepairStatus.INFEASIBLE) {
            wide = true
            result = DayRepair.repair(repairMeals, targets, RepairLimits.WIDE, tolerance)
        }
        when (result.status) {
            RepairStatus.REPAIRED -> Unit
            RepairStatus.INFEASIBLE -> return Outcome.Fallback(FallbackReason.INFEASIBLE)
            else -> return Outcome.Fallback(FallbackReason.OUT_OF_TOLERANCE)
        }

        val drafts = day.meals.mapIndexed { index, meal ->
            if (index !in adjustable) return@mapIndexed meal.toDraft()
            val grams = result.mealGrams[repairIndex.indexOf(index)]
            rebuildMeal(meal, resolvedByMeal.getValue(index), grams)
        }

        // Verifica sui valori PERSISTITI (arrotondati): il giorno e la somma dei pasti.
        val persistedKcal = drafts.sumOf { (it.kcal ?: 0).toDouble() } + day.supplements.sumOf { it.kcal.toDouble() }
        val deviation = (persistedKcal - targetKcal) / targetKcal
        if (abs(deviation) > ValidationPolicy.FIRST_ATTEMPT_TOLERANCE + EPS) return Outcome.Fallback(FallbackReason.OUT_OF_TOLERANCE)
        return Outcome.Computed(drafts, wide, deviation)
    }

    /** Pasto con grammi (1 decimale, come il piano), snapshot, dosi e totali ricalcolati; COMPUTED, titolo e preparazione invariati. */
    private fun rebuildMeal(meal: FoodMeal, resolved: List<Resolved>, grams: List<Double>): MealDraft {
        val base = meal.toDraft()
        val ingredients: List<IngredientDraft> = resolved.mapIndexed { i, r ->
            val g = Math.round(grams[i] * 10.0) / 10.0
            val draft = base.ingredients[i]
            if (abs(g - r.ingredient.grams!!) <= UNCHANGED_G) {
                draft
            } else {
                val gen = builder.generatedIngredient(r.food, g)
                draft.copy(
                    quantity = gen.quantity, displayDose = gen.displayDose, grams = gen.grams,
                    kcal = gen.kcal, proteinG = gen.proteinG, carbsG = gen.carbsG, fatG = gen.fatG,
                    fiberG = gen.fiberG, sodiumMg = gen.sodiumMg,
                )
            }
        }
        fun sum(f: (IngredientDraft) -> Float?) = ingredients.sumOf { (f(it) ?: 0f).toDouble() }
        return base.copy(
            kcal = Math.round(sum { it.kcal }).toInt(),
            proteinG = round1(sum { it.proteinG }).toFloat(),
            carbsG = round1(sum { it.carbsG }).toFloat(),
            fatG = round1(sum { it.fatG }).toFloat(),
            ingredients = ingredients,
            nutritionSource = FoodMeal.NUTRITION_SOURCE_COMPUTED,
        )
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0

    private companion object {
        const val EPS = 1e-9
        const val UNCHANGED_G = 0.05
    }
}
