package com.myfitai.app.domain.calculation

import kotlin.math.max

/**
 * UNICA definizione del minimo calorico di sicurezza. Motore target, adattamento, recupero sgarro,
 * piano e flag di sicurezza dell'IA leggono tutti da qui.
 *
 * Regola approvata dal prodotto (15/11/2026):
 * minimo = max(BMR locale, 1200 kcal donne / 1500 kcal uomini e sesso non dichiarato).
 * Il totale giornaliero non può scendere sotto questo pavimento per effetto della tolleranza del generatore.
 * Si applica solo a obiettivi che riducono l'apporto (fattore sul TDEE < 1): mantenimento,
 * aumento e performance partono da un target >= TDEE e non vengono mai alzati dal minimo.
 */
object SafetyCalorieFloor {
    const val MIN_KCAL_FEMALE = 1200.0
    const val MIN_KCAL_OTHER = 1500.0

    /** Soglia assoluta per sesso; sesso mancante = prudenza maggiore (stessa regola del flag IA). */
    fun absoluteMinimumKcal(sex: LocalCalculationEngine.BiologicalSex?): Double =
        if (sex == LocalCalculationEngine.BiologicalSex.FEMALE) MIN_KCAL_FEMALE else MIN_KCAL_OTHER

    /** Minimo applicabile: mai sotto il BMR locale (se noto) ne sotto la soglia assoluta. */
    fun minimumKcal(bmrKcal: Double?, sex: LocalCalculationEngine.BiologicalSex?): Double =
        max(bmrKcal?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0, absoluteMinimumKcal(sex))

    /** True se l'obiettivo riduce l'apporto rispetto al TDEE e quindi il minimo ha senso. */
    fun appliesTo(goal: LocalCalculationEngine.Goal?): Boolean =
        goal != null && LocalCalculationEngine.goalEnergyFactor(goal) < 1.0

    data class Clamp(val kcal: Double, val applied: Boolean)

    /** Porta [kcal] al minimo se inferiore; [applied] dichiara che il minimo ha cambiato il valore. */
    fun clamp(kcal: Double, minimumKcal: Double?): Clamp =
        if (minimumKcal != null && minimumKcal.isFinite() && kcal < minimumKcal) Clamp(minimumKcal, true) else Clamp(kcal, false)

    /** Maximum whole-kcal reduction that keeps a reduction-goal target above both safety bounds. */
    fun maximumSafeDailyReductionKcal(
        targetKcal: Int,
        tdeeKcal: Double?,
        minimumKcal: Double?,
        maximumDailyDeficitKcal: Double,
    ): Int {
        if (targetKcal <= 0 || tdeeKcal == null || !tdeeKcal.isFinite() || tdeeKcal <= 0.0 ||
            minimumKcal == null || !minimumKcal.isFinite() || minimumKcal < 0.0 ||
            !maximumDailyDeficitKcal.isFinite() || maximumDailyDeficitKcal < 0.0
        ) return 0

        val minimumAllowedTarget = maxOf(minimumKcal, tdeeKcal - maximumDailyDeficitKcal)
        // Truncation is intentional: rounding up could cross the floor or exceed the deficit cap.
        return (targetKcal - minimumAllowedTarget).toInt().coerceAtLeast(0)
    }
}
