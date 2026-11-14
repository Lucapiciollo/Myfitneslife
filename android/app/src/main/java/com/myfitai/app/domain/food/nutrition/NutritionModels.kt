package com.myfitai.app.domain.food.nutrition

/**
 * Tipi di ingresso del nucleo di calcolo nutrizionale (lotto MACRO L2).
 *
 * Sono volutamente autonomi: non dipendono dal catalogo alimenti né da Room. L'integrazione con
 * le voci del catalogo (FoodEntry) è un adattatore che appartiene al lotto di integrazione.
 *
 * Convenzione di precisione: TUTTI i calcoli sono in `Double`. L'arrotondamento (kcal intere, macro a
 * 1 decimale, sodio in mg interi) avviene solo in presentazione/persistenza tramite
 * [NutrientTotals.rounded]; i confronti con i target (validatore, riparazione) usano i valori non arrotondati.
 */

/** Valori nutrizionali per 100 g di alimento. Il sodio è in mg, gli altri nutrienti in g (kcal in kcal). */
data class Per100(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val waterG: Double = 0.0,
)

/**
 * Una voce di pasto in ingresso al calcolo/riparazione. [grams] è SEMPRE in grammi: i ml vanno
 * convertiti prima con [NutritionCalculator.gramsFromMl] usando [densityGPerMl].
 *
 * @param minG limite inferiore assoluto (catalogo) per la riparazione, null = nessuno.
 * @param maxG limite superiore assoluto (catalogo) per la riparazione, null = nessuno.
 * @param stepG passo pratico di pesatura usato dall'arrotondamento della riparazione (> 0).
 * @param scalable false = la riparazione non può cambiare i grammi (es. integratori, sale).
 * @param locked true = grammi bloccati (es. scelta dell'utente), come non scalabile.
 */
data class IngredientInput(
    val foodId: String,
    val per100: Per100,
    val grams: Double,
    val minG: Double? = null,
    val maxG: Double? = null,
    val stepG: Double = 1.0,
    val scalable: Boolean = true,
    val densityGPerMl: Double? = null,
    val locked: Boolean = false,
) {
    /** True se la riparazione può modificare i grammi di questa voce. */
    val isAdjustable: Boolean get() = scalable && !locked && grams > 0.0

    companion object {
        /** Passo pratico di default dello spike S1: 5 g dai 20 g in su, 1 g sotto. */
        fun practicalStep(grams: Double): Double = if (grams >= 20.0) 5.0 else 1.0
    }
}

/** Totali nutrizionali non arrotondati (kcal, grammi, mg di sodio). */
data class NutrientTotals(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double = 0.0,
    val sodiumMg: Double = 0.0,
) {
    operator fun plus(other: NutrientTotals) = NutrientTotals(
        kcal + other.kcal,
        proteinG + other.proteinG,
        carbsG + other.carbsG,
        fatG + other.fatG,
        fiberG + other.fiberG,
        sodiumMg + other.sodiumMg,
    )

    /** Arrotondamento di presentazione/persistenza: kcal e sodio interi, macro e fibre a 1 decimale (half-up). */
    fun rounded() = RoundedTotals(
        kcal = Math.round(kcal).toInt(),
        proteinG = round1(proteinG),
        carbsG = round1(carbsG),
        fatG = round1(fatG),
        fiberG = round1(fiberG),
        sodiumMg = Math.round(sodiumMg).toInt(),
    )

    companion object {
        val ZERO = NutrientTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    }
}

/** Totali arrotondati per presentazione/persistenza (vedi [NutrientTotals.rounded]). */
data class RoundedTotals(
    val kcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val sodiumMg: Int,
)
