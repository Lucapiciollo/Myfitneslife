package com.myfitai.app.domain.food.nutrition

import kotlin.math.abs

/**
 * Politica di validazione pura sui totali CALCOLATI dal catalogo (decisione di prodotto):
 * BLOCCA solo lo scarto delle kcal oltre la tolleranza (±3% al primo tentativo, ±4% nei retry);
 * proteine, carboidrati e grassi producono solo avvisi non bloccanti con il loro scarto.
 * Nessuna dipendenza da Android/Room: funzione pura e testabile su JVM.
 */
object ValidationPolicy {
    const val FIRST_ATTEMPT_TOLERANCE = 0.03
    const val RETRY_TOLERANCE = 0.04

    private const val EPS = 1e-9

    enum class Macro { PROTEIN, CARBS, FAT }

    /** Avviso non bloccante: [deviation] è relativo con segno (calcolato - target) / target. */
    data class MacroWarning(
        val macro: Macro,
        val targetG: Double,
        val calculatedG: Double,
        val deviation: Double,
        val deviationG: Double,
    )

    /**
     * @param isBlocking true solo se le kcal sono fuori tolleranza (o il target kcal non è valido).
     * @param kcalDeviation scarto relativo con segno delle kcal ((calcolato - target) / target).
     */
    data class Verdict(
        val isBlocking: Boolean,
        val kcalDeviation: Double,
        val tolerance: Double,
        val warnings: List<MacroWarning>,
    ) {
        val isAccepted: Boolean get() = !isBlocking
    }

    /** Solo kcal: nessun avviso sui macro. */
    fun evaluate(totals: NutrientTotals, targetKcal: Double, tolerance: Double): Verdict =
        evaluate(totals, DayTargets(kcal = targetKcal), tolerance)

    /**
     * Valuta i totali calcolati. Gli avvisi su proteine/carboidrati/grassi compaiono solo se il relativo
     * target è > 0 e lo scarto relativo supera [tolerance]; non influiscono mai su [Verdict.isBlocking].
     */
    fun evaluate(totals: NutrientTotals, targets: DayTargets, tolerance: Double): Verdict {
        require(tolerance >= 0.0) { "tolerance deve essere >= 0" }
        if (targets.kcal <= 0.0) {
            return Verdict(isBlocking = true, kcalDeviation = Double.POSITIVE_INFINITY, tolerance = tolerance, warnings = emptyList())
        }
        val kcalDev = (totals.kcal - targets.kcal) / targets.kcal
        val warnings = buildList {
            warn(Macro.PROTEIN, targets.proteinG, totals.proteinG, tolerance)?.let(::add)
            warn(Macro.CARBS, targets.carbsG, totals.carbsG, tolerance)?.let(::add)
            warn(Macro.FAT, targets.fatG, totals.fatG, tolerance)?.let(::add)
        }
        return Verdict(
            isBlocking = abs(kcalDev) > tolerance + EPS,
            kcalDeviation = kcalDev,
            tolerance = tolerance,
            warnings = warnings,
        )
    }

    private fun warn(macro: Macro, target: Double, calculated: Double, tolerance: Double): MacroWarning? {
        if (target <= 0.0) return null
        val dev = (calculated - target) / target
        if (abs(dev) <= tolerance + EPS) return null
        return MacroWarning(macro, target, calculated, dev, calculated - target)
    }
}
