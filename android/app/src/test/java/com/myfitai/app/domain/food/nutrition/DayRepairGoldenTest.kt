package com.myfitai.app.domain.food.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Golden set di 60 giorni sintetici deterministici (semi 5000..5059): 50 fattibili per costruzione
 * (25 con target P/C/F coerenti con la verità, 25 con target P/C/F perturbati ±15%) e 10 infeasible
 * (target kcal 2,5x o 0,3x delle kcal iniziali).
 *
 * Soglie dichiarate:
 *  - fattibili: kcal entro ±3% nel 100% dei giorni, entro ±1% in almeno il 95%, stato REPAIRED al 100%;
 *  - vincoli duri (scala per voce, ±40% per pasto, voci bloccate, pasti consumati) rispettati al 100%;
 *  - target proteico coerente: proteine entro ±3% in almeno il 85% (obiettivo di riparazione, non bloccante);
 *  - infeasible: stato INFEASIBLE al 100% con intervallo kcal raggiungibile che non contiene il target.
 */
class DayRepairGoldenTest {
    private val feasibleCount = 50
    private val infeasibleCount = 10

    @Test
    fun goldenSet_meetsDeclaredThresholds() {
        var kcal3 = 0
        var kcal1 = 0
        var repaired = 0
        var limitsOk = 0
        var protein3Exact = 0
        var exactDays = 0
        var protein3All = 0
        for (i in 0 until feasibleCount) {
            val day = NutritionFixtures.syntheticDay(5000L + i, feasible = true, exactMacros = i % 2 == 0)
            val r = DayRepair.repair(day.meals, day.targets)
            if (abs(r.kcalDeviation) <= 0.03 + 1e-9) kcal3++
            if (abs(r.kcalDeviation) <= 0.01 + 1e-9) kcal1++
            if (r.status == RepairStatus.REPAIRED) repaired++
            if (NutritionFixtures.violations(day.meals, r, RepairLimits.DEFAULT).isEmpty()) limitsOk++
            if (abs(r.proteinDeviation) <= 0.03 + 1e-9) protein3All++
            if (day.exactMacros) {
                exactDays++
                if (abs(r.proteinDeviation) <= 0.03 + 1e-9) protein3Exact++
            }
        }
        var infeasibleOk = 0
        for (i in 0 until infeasibleCount) {
            val day = NutritionFixtures.syntheticDay(5000L + feasibleCount + i, feasible = false)
            val r = DayRepair.repair(day.meals, day.targets)
            val outside = day.targets.kcal > r.reachableKcal.maxKcal || day.targets.kcal < r.reachableKcal.minKcal
            val limits = NutritionFixtures.violations(day.meals, r, RepairLimits.DEFAULT).isEmpty()
            if (r.status == RepairStatus.INFEASIBLE && outside && limits) infeasibleOk++
        }
        println(
            "GOLDEN: fattibili=$feasibleCount kcal±3%=${pct(kcal3, feasibleCount)} kcal±1%=${pct(kcal1, feasibleCount)} " +
                "REPAIRED=${pct(repaired, feasibleCount)} vincoli=${pct(limitsOk, feasibleCount)} " +
                "proteine±3%(coerenti)=${pct(protein3Exact, exactDays)} proteine±3%(tutti)=${pct(protein3All, feasibleCount)} " +
                "infeasible=${pct(infeasibleOk, infeasibleCount)}",
        )
        assertEquals("kcal ±3% sui fattibili", feasibleCount, kcal3)
        assertTrue("kcal ±1% = $kcal1/$feasibleCount", kcal1 >= 0.95 * feasibleCount)
        assertEquals("stato REPAIRED", feasibleCount, repaired)
        assertEquals("vincoli duri", feasibleCount, limitsOk)
        assertTrue("proteine ±3% coerenti = $protein3Exact/$exactDays", protein3Exact >= 0.85 * exactDays)
        assertEquals("infeasible riconosciuti", infeasibleCount, infeasibleOk)
    }

    private fun pct(n: Int, d: Int) = "%.1f%%".format(100.0 * n / d)
}
