package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import com.myfitai.app.domain.calculation.LocalCalculationEngine.MacroTargets
import kotlin.math.max
import kotlin.math.min

/**
 * UNICA politica dei macro del target (K6). IPOTESI DI PRODOTTO REVERSIBILE: tutti i numeri sono qui e in
 * nessun altro punto; per tornare alla vecchia regola (proteine 2,0 g/kg, grassi 0,8 g/kg) basta cambiare
 * queste costanti e il corpo di [allocate].
 *
 * Motivo: con kcal basse la vecchia regola dava proteine = 46% delle kcal e carboidrati ~41 g/giorno
 * (donna 78 kg, 1381 kcal: 160 g proteine, 41 g carboidrati).
 *
 * Regola, calcolata UNA volta sul target abituale del profilo ([allocate]):
 * - proteine = min(g/kg dell'obiettivo, 30% delle kcal), ma mai sotto 1,2 g/kg;
 * - grassi = max(0,6 g/kg, 20% delle kcal), ma mai oltre il 35% delle kcal;
 * - carboidrati per differenza, con minimo 100 g se le kcal sono >= 1500 e 80 g sotto.
 *
 * Se il minimo di carboidrati non si raggiunge, ordine di priorita (dichiarato e testato):
 * 1. si riducono i grassi, fino al 20% delle kcal;
 * 2. poi le proteine, fino a 1,2 g/kg;
 * 3. se ancora non basta, i carboidrati restano quelli che avanzano (mai negativi): la sicurezza proteica e
 *    lipidica prevale sul minimo carboidrati.
 *
 * Invarianti dei motori giornalieri (proteine uguali tra riposo e allenamento, le proteine non scalano con le
 * calorie, il recupero sgarro non riduce le proteine): proteine e grassi dell'[Anchor] restano FISSI su tutti i
 * giorni e solo i carboidrati assorbono la differenza ([targetsFor]).
 */
object MacroPolicy {
    const val PROTEIN_MAX_KCAL_SHARE = 0.30
    const val PROTEIN_MIN_PER_KG = 1.2
    const val FAT_MIN_PER_KG = 0.6
    const val FAT_MIN_KCAL_SHARE = 0.20
    const val FAT_MAX_KCAL_SHARE = 0.35
    const val CARBS_MIN_G = 100.0
    const val CARBS_MIN_G_LOW_KCAL = 80.0
    const val LOW_KCAL_THRESHOLD = 1500.0

    private const val PROTEIN_KCAL_PER_G = 4.0
    private const val CARBS_KCAL_PER_G = 4.0
    private const val FAT_KCAL_PER_G = 9.0

    /** Proteine e grassi fissati sul target abituale; i giorni derivati cambiano solo i carboidrati. */
    data class Anchor(val proteinG: Double, val fatG: Double) {
        init {
            require(proteinG.isFinite() && proteinG >= 0.0 && fatG.isFinite() && fatG >= 0.0) { "MACRO_ANCHOR_INVALID" }
        }
    }

    fun proteinPerKgCap(goal: Goal): Double = when (goal) {
        Goal.WEIGHT_LOSS, Goal.RECOMPOSITION, Goal.MUSCLE_GAIN -> 2.0
        Goal.MAINTENANCE, Goal.PERFORMANCE -> 1.8
    }

    fun minimumCarbsG(targetKcal: Double): Double =
        if (targetKcal >= LOW_KCAL_THRESHOLD) CARBS_MIN_G else CARBS_MIN_G_LOW_KCAL

    /** Macro completi per il target abituale (kcal gia deciso localmente). */
    fun allocate(targetKcal: Double, weightKg: Double, goal: Goal): MacroTargets {
        require(targetKcal.isFinite() && targetKcal > 0.0) { "TARGET_KCAL_INVALID" }
        require(weightKg.isFinite() && weightKg > 0.0) { "WEIGHT_INVALID" }

        val proteinMin = PROTEIN_MIN_PER_KG * weightKg
        var proteinG = max(min(proteinPerKgCap(goal) * weightKg, PROTEIN_MAX_KCAL_SHARE * targetKcal / PROTEIN_KCAL_PER_G), proteinMin)
        val fatFloor = FAT_MIN_KCAL_SHARE * targetKcal / FAT_KCAL_PER_G
        var fatG = min(max(FAT_MIN_PER_KG * weightKg, fatFloor), FAT_MAX_KCAL_SHARE * targetKcal / FAT_KCAL_PER_G)

        val carbsMin = minimumCarbsG(targetKcal)
        fun carbs() = (targetKcal - proteinG * PROTEIN_KCAL_PER_G - fatG * FAT_KCAL_PER_G) / CARBS_KCAL_PER_G
        if (carbs() < carbsMin) {
            // 1) grassi fino al 20% delle kcal.
            val missingKcal = (carbsMin - carbs()) * CARBS_KCAL_PER_G
            fatG = max(min(fatG, fatFloor), fatG - missingKcal / FAT_KCAL_PER_G)
            // 2) proteine fino a 1,2 g/kg.
            if (carbs() < carbsMin) {
                val stillMissingKcal = (carbsMin - carbs()) * CARBS_KCAL_PER_G
                proteinG = max(min(proteinG, proteinMin), proteinG - stillMissingKcal / PROTEIN_KCAL_PER_G)
            }
        }
        return MacroTargets(proteinG, fatG, max(0.0, carbs()))
    }

    fun anchorOf(macros: MacroTargets): Anchor = Anchor(macros.proteinG, macros.fatG)

    /** Anchor dal target abituale (la stessa regola di [allocate], senza i carboidrati). */
    fun anchorFor(targetKcal: Double, weightKg: Double, goal: Goal): Anchor = anchorOf(allocate(targetKcal, weightKg, goal))

    /** Macro di un giorno: proteine e grassi dell'anchor, carboidrati per differenza (mai negativi). */
    fun targetsFor(targetKcal: Double, anchor: Anchor): MacroTargets {
        require(targetKcal.isFinite() && targetKcal > 0.0) { "TARGET_KCAL_INVALID" }
        val carbsG = max(0.0, (targetKcal - anchor.proteinG * PROTEIN_KCAL_PER_G - anchor.fatG * FAT_KCAL_PER_G) / CARBS_KCAL_PER_G)
        return MacroTargets(anchor.proteinG, anchor.fatG, carbsG)
    }
}
