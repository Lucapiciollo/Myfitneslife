package com.myfitai.app.domain.food.nutrition

/**
 * Calcolo deterministico dei nutrienti: ingrediente = per100 x grammi / 100; pasto = somma degli
 * ingredienti; giorno = somma dei pasti. Funzioni pure, tutto in `Double` (nessun arrotondamento qui).
 */
object NutritionCalculator {

    /** Grammi equivalenti a [ml] millilitri dato il peso specifico [densityGPerMl] (g/ml). */
    fun gramsFromMl(ml: Double, densityGPerMl: Double): Double {
        require(densityGPerMl > 0.0) { "densityGPerMl deve essere > 0" }
        return ml * densityGPerMl
    }

    fun ingredient(per100: Per100, grams: Double): NutrientTotals {
        val f = grams / 100.0
        return NutrientTotals(
            kcal = per100.kcal * f,
            proteinG = per100.proteinG * f,
            carbsG = per100.carbsG * f,
            fatG = per100.fatG * f,
            fiberG = per100.fiberG * f,
            sodiumMg = per100.sodiumMg * f,
        )
    }

    fun ingredient(input: IngredientInput): NutrientTotals = ingredient(input.per100, input.grams)

    fun meal(ingredients: List<IngredientInput>): NutrientTotals =
        ingredients.fold(NutrientTotals.ZERO) { acc, i -> acc + ingredient(i) }

    fun day(meals: List<List<IngredientInput>>): NutrientTotals =
        meals.fold(NutrientTotals.ZERO) { acc, m -> acc + meal(m) }

    /** Totali di un pasto con grammi diversi da quelli di ingresso (stessa lunghezza di [ingredients]). */
    fun mealWithGrams(ingredients: List<IngredientInput>, grams: List<Double>): NutrientTotals {
        require(ingredients.size == grams.size) { "grammi e ingredienti devono avere la stessa lunghezza" }
        var acc = NutrientTotals.ZERO
        for (i in ingredients.indices) acc += ingredient(ingredients[i].per100, grams[i])
        return acc
    }
}
