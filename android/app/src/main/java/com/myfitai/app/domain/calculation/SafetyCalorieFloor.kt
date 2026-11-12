package com.myfitai.app.domain.calculation

import kotlin.math.max

/**
 * UNICA definizione del minimo calorico di sicurezza. Motore target, adattamento, recupero sgarro,
 * piano e flag di sicurezza dell'IA leggono tutti da qui.
 *
 * IPOTESI REVERSIBILE da confermare dal responsabile prodotto:
 * minimo = max(BMR locale, 1200 kcal donne / 1500 kcal uomini e sesso non dichiarato).
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
}
