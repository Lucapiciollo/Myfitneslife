package com.myfitai.app.domain.food.nutrition

/** Target del giorno per la riparazione/validazione. I macro a 0 (o negativi) sono ignorati come obiettivo. */
data class DayTargets(
    val kcal: Double,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
)

/**
 * Limiti configurabili della riparazione per giorno.
 *
 * @param lowFactor grammi minimi per voce = lowFactor x proposta (intersecato con minG/maxG).
 * @param highFactor grammi massimi per voce = highFactor x proposta.
 * @param mealKcalFraction ogni pasto resta entro ±mealKcalFraction delle sue kcal originali.
 * @param maxSweeps passate massime della discesa a coordinate per ciascuna ripresa (fino a 4 riprese con peso kcal crescente).
 * @param convergenceG la discesa si ferma quando nessuna voce si sposta più di tanti grammi in una passata.
 */
data class RepairLimits(
    val lowFactor: Double = 0.6,
    val highFactor: Double = 1.5,
    val mealKcalFraction: Double = 0.4,
    val maxSweeps: Int = 200,
    val convergenceG: Double = 0.5,
) {
    init {
        require(lowFactor in 0.0..1.0) { "lowFactor deve stare in [0, 1]" }
        require(highFactor >= 1.0) { "highFactor deve essere >= 1" }
        require(mealKcalFraction >= 0.0) { "mealKcalFraction deve essere >= 0" }
        require(maxSweeps > 0 && convergenceG > 0.0)
    }

    companion object {
        /** Default del progetto (spike S1): 0,6x-1,5x per voce, ±40% per pasto. */
        val DEFAULT = RepairLimits()

        /** Preset "wide" dello spike: 0,5x-2,0x per voce, ±60% per pasto. */
        val WIDE = RepairLimits(lowFactor = 0.5, highFactor = 2.0, mealKcalFraction = 0.6)
    }
}

/**
 * Un pasto del giorno da riparare. [consumed] = pasto già consumato/passato: tutte le voci sono bloccate
 * (contano nei totali del giorno ma non vengono modificate e non hanno limiti di scala).
 */
data class RepairMeal(
    val id: String,
    val ingredients: List<IngredientInput>,
    val consumed: Boolean = false,
)

/** Fascia di scarto relativo rispetto al target. */
enum class DeviationBand { WITHIN_1_PERCENT, WITHIN_3_PERCENT, OUTSIDE }

enum class RepairStatus {
    /** Le kcal finali sono entro la tolleranza richiesta. */
    REPAIRED,

    /** La finestra kcal ±tolleranza non interseca l'intervallo raggiungibile: va rimandato al modello. */
    INFEASIBLE,

    /** Teoricamente raggiungibile ma il solutore non è arrivato entro la tolleranza (non atteso). */
    NOT_CONVERGED,
}

/** Intervallo kcal giornaliero raggiungibile rispettando tutti i limiti (voci bloccate comprese). */
data class KcalRange(val minKcal: Double, val maxKcal: Double)

/**
 * Esito della riparazione di un giorno.
 *
 * @param mealGrams grammi finali, stessa struttura di `meals` (pasto -> voce).
 * @param totals totali del giorno ricalcolati dai grammi finali (non arrotondati).
 * @param kcalDeviation scarto relativo con segno (calcolato - target) / target.
 * @param proteinDeviation idem per le proteine (0 se il target proteico non è impostato).
 * @param reachableKcal intervallo kcal raggiungibile, sempre valorizzato: se [status] è INFEASIBLE è quello
 *   da comunicare al modello.
 */
data class RepairResult(
    val status: RepairStatus,
    val mealGrams: List<List<Double>>,
    val totals: NutrientTotals,
    val kcalBand: DeviationBand,
    val proteinBand: DeviationBand,
    val kcalDeviation: Double,
    val proteinDeviation: Double,
    val iterations: Int,
    val reachableKcal: KcalRange,
)
