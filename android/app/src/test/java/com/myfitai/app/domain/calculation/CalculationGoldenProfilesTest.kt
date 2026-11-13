package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.body.BiaAnalysisReport
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden dei profili D e U dell'audit calcoli e della politica dei macro [MacroPolicy] (K6, ipotesi reversibile).
 * Se una formula cambia, questi numeri cambiano e vanno rivisti deliberatamente (non aggiornati a occhio).
 *
 * Politica: proteine = max(min(g/kg obiettivo, 30% kcal / 4), 1,2 g/kg); grassi = min(max(0,6 g/kg, 20% kcal / 9),
 * 35% kcal / 9); carboidrati = (kcal - 4 P - 9 G) / 4, minimo 100 g (>= 1500 kcal) o 80 g (sotto).
 */
class CalculationGoldenProfilesTest {

    /**
     * D: donna 62 anni, 164 cm, 78 kg, moderatamente attiva, dimagrimento. Ricalcolo a mano:
     * BMR = 10*78 + 6,25*164 - 5*62 - 161 = 1334; TDEE = 1334 * 1,55 = 2067,7; target = 2067,7 * 0,85 = 1757,545.
     * Proteine = min(2,0*78 = 156; 0,30*1757,545/4 = 131,816) = 131,816 (>= 1,2*78 = 93,6).
     * Grassi = max(0,6*78 = 46,8; 0,20*1757,545/9 = 39,057) = 46,8 (<= 0,35*1757,545/9 = 68,34).
     * Carboidrati = (1757,545 - 527,266 - 421,2) / 4 = 202,27 (prima 143,0).
     */
    @Test
    fun profileD_woman62_164cm_78kg_weightLoss() {
        val r = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 78.0,
                heightCm = 164.0,
                ageYears = 62,
                biologicalSex = LocalCalculationEngine.BiologicalSex.FEMALE,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = Goal.WEIGHT_LOSS,
            )
        )
        assertEquals("MIFFLIN_ST_JEOR", r.bmrMethod)
        assertEquals(1334.0, r.bmrKcal!!, 0.05)
        assertEquals(2067.7, r.tdeeKcal!!, 0.05)
        assertEquals(1757.5, r.targetKcal!!, 0.1)
        assertEquals(131.8, r.proteinG!!, 0.1)
        assertEquals(46.8, r.fatG!!, 0.1)
        assertEquals(202.27, r.carbsG!!, 0.1)
        assertFalse(r.floorApplied)
    }

    /**
     * Caso reale osservato sul dispositivo: la stessa donna SEDENTARIA ha target 1381 kcal (valore osservato; il
     * ricalcolo del motore da' 1360,68). Vecchia regola: 160 g di proteine (46% delle kcal), grassi ~62 g, carboidrati
     * 41 g. Ricalcolo a mano a 1381 kcal e 78 kg:
     * proteine = min(156; 0,30*1381/4 = 103,575) = 103,575 (30% delle kcal; >= 93,6);
     * grassi = max(46,8; 0,20*1381/9 = 30,69) = 46,8 (30,5% delle kcal);
     * carboidrati = (1381 - 414,3 - 421,2) / 4 = 136,375 (minimo 80 g sotto 1500 kcal: rispettato).
     */
    @Test
    fun realCase_womanSedentary_1381kcal_noLongerGetsFortyOneGramsOfCarbs() {
        val m = LocalCalculationEngine.calculateMacrosForTarget(1381.0, 78.0, Goal.WEIGHT_LOSS)
        assertEquals(103.575, m.proteinG, 0.01)
        assertEquals(46.8, m.fatG, 0.01)
        assertEquals(136.375, m.carbsG, 0.01)
        assertTrue(m.proteinG * 4.0 / 1381.0 <= 0.3 + 1e-9)
        assertEquals(1381.0, m.proteinG * 4 + m.fatG * 9 + m.carbsG * 4, 1e-6)
    }

    /**
     * U: uomo 40 anni, 180 cm, 89,5 kg, moderatamente attivo, dimagrimento. Ricalcolo a mano:
     * BMR = 895 + 1125 - 200 + 5 = 1825; TDEE = 2828,75; target = 2404,4375.
     * Proteine = min(179; 0,30*2404,4375/4 = 180,33) = 179 (tetto g/kg); grassi = max(53,7; 53,43) = 53,7;
     * carboidrati = (2404,4375 - 716 - 483,3) / 4 = 301,28 (prima 261,0).
     */
    @Test
    fun profileU_man40_180cm_89_5kg_weightLoss() {
        val r = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 89.5,
                heightCm = 180.0,
                ageYears = 40,
                biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = Goal.WEIGHT_LOSS,
            )
        )
        assertEquals("MIFFLIN_ST_JEOR", r.bmrMethod)
        assertEquals(1825.0, r.bmrKcal!!, 0.05)
        assertEquals(2828.8, r.tdeeKcal!!, 0.06)
        assertEquals(2404.4, r.targetKcal!!, 0.1)
        assertEquals(179.0, r.proteinG!!, 0.1)
        assertEquals(53.7, r.fatG!!, 0.1)
        assertEquals(301.28, r.carbsG!!, 0.1)
        assertFalse(r.floorApplied)
    }

    /**
     * Luca: uomo 43 anni, 186 cm, 89,5 kg, grasso 19,9% (BIA), "Moderatamente attivo", dimagrimento. Ricalcolo a mano:
     * massa magra = 89,5 * (1 - 0,199) = 71,6895; BMR Katch-McArdle = 370 + 21,6 * 71,6895 = 1918,5;
     * TDEE = 1918,5 * 1,55 = 2973,7; target = 2973,7 * 0,85 = 2527,6.
     * Proteine = min(179; 0,30*2527,6/4 = 189,6) = 179; grassi = max(53,7; 0,20*2527,6/9 = 56,17) = 56,17;
     * carboidrati = (2527,6 - 716 - 505,5) / 4 = 326,5.
     * Il livello di attivita e UNO solo e vale per tutti i giorni: stesso target in Home, Cibo, BIA Coach e piano.
     */
    @Test
    fun profileLuca_man43_186cm_89_5kg_katch_moderate_weightLoss_sameTargetEverywhere() {
        val r = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 89.5,
                heightCm = 186.0,
                ageYears = 43,
                biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
                bodyFatPercent = 19.9,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = Goal.WEIGHT_LOSS,
            )
        )
        assertEquals("KATCH_MCARDLE", r.bmrMethod)
        assertEquals(1918.5, r.bmrKcal!!, 0.1)
        assertEquals(2973.7, r.tdeeKcal!!, 0.2)
        assertEquals(2527.6, r.targetKcal!!, 0.2)
        assertEquals(179.0, r.proteinG!!, 0.1)
        assertEquals(56.17, r.fatG!!, 0.1)
        assertEquals(326.5, r.carbsG!!, 0.5)
        assertFalse(r.floorApplied)

        // Home and Food: the profile numbers, no check-in, no "rest day" TDEE (BMR x 1,2 would be 2302).
        val shown = OperationalNutritionMetrics.calculate(r)
        assertEquals(r.tdeeKcal!!, shown.tdeeKcal!!, 0.0)
        assertEquals(r.targetKcal!!, shown.targetKcal!!, 0.0)
        assertEquals(r.proteinG!!, shown.proteinG!!, 0.0)
        assertEquals(r.carbsG!!, shown.carbsG!!, 0.0)
        assertEquals(r.fatG!!, shown.fatG!!, 0.0)
        // BIA Coach: the same figures, written once as the habitual target.
        val bia = BiaAnalysisReport.caloriesSection(r)
        assertTrue(bia, bia.startsWith("Target abituale: 2528 kcal, proteine 179 g"))
        assertTrue(bia, bia.contains("Consumo abituale stimato (TDEE): 2974 kcal"))
    }

    // ---- MacroPolicy: ordine di priorita --------------------------------------------------------

    @Test
    fun policy_carbsMinimumReducesFatFirstDownToTwentyPercentOfKcal() {
        // 130 kg, 1500 kcal: proteine = max(min(260; 112,5); 156) = 156; grassi = min(max(78; 33,3); 58,33) = 58,33;
        // carbo = (1500 - 624 - 525) / 4 = 87,75 < 100. Mancano 12,25 g = 49 kcal: grassi 58,33 - 49/9 = 52,89
        // (sopra il 20% = 33,3 g), proteine invariate, carbo = 100.
        val m = MacroPolicy.allocate(1500.0, 130.0, Goal.WEIGHT_LOSS)
        assertEquals(156.0, m.proteinG, 1e-9)
        assertEquals(58.3333 - 49.0 / 9.0, m.fatG, 0.001)
        assertEquals(100.0, m.carbsG, 1e-6)
    }

    @Test
    fun policy_thenProteinDownToOnePointTwoPerKg() {
        // 30 kg, 600 kcal (minimo carbo 80 g): proteine = max(min(60; 45); 36) = 45; grassi = min(max(18; 13,33); 23,3) = 18;
        // carbo = 64,5. 1) grassi al 20% = 13,33 g (carbo 75); 2) mancano 5 g = 20 kcal: proteine 45 - 5 = 40 (>= 36); carbo 80.
        val m = MacroPolicy.allocate(600.0, 30.0, Goal.WEIGHT_LOSS)
        assertEquals(40.0, m.proteinG, 1e-6)
        assertEquals(0.2 * 600.0 / 9.0, m.fatG, 1e-6)
        assertEquals(80.0, m.carbsG, 1e-6)
    }

    @Test
    fun policy_whenTheCarbsMinimumIsUnreachableProteinAndFatFloorsWin() {
        // 200 kg, 1500 kcal: proteine al minimo 1,2*200 = 240, grassi al 20% = 33,33: carbo = (1500 - 960 - 300) / 4 = 60 < 100.
        val m = MacroPolicy.allocate(1500.0, 200.0, Goal.WEIGHT_LOSS)
        assertEquals(240.0, m.proteinG, 1e-9)
        assertEquals(300.0 / 9.0, m.fatG, 1e-9)
        assertEquals(60.0, m.carbsG, 1e-6)
    }

    @Test
    fun policy_thresholdMinimumsAndMaintenanceProteinPerKg() {
        assertEquals(100.0, MacroPolicy.minimumCarbsG(1500.0), 0.0)
        assertEquals(80.0, MacroPolicy.minimumCarbsG(1499.9), 0.0)
        // Mantenimento: tetto 1,8 g/kg (80 kg -> 144 g) con 2500 kcal (30% = 187,5 g): resta 144.
        val m = MacroPolicy.allocate(2500.0, 80.0, Goal.MAINTENANCE)
        assertEquals(144.0, m.proteinG, 1e-9)
        assertEquals(0.2 * 2500.0 / 9.0, m.fatG, 1e-9) // 55,6 g > 0,6*80 = 48
    }

    @Test
    fun policy_dayMacrosKeepProteinAndFatAndOnlyCarbsMove() {
        val anchor = MacroPolicy.anchorFor(1381.0, 78.0, Goal.WEIGHT_LOSS)
        val lowDay = MacroPolicy.targetsFor(1243.0, anchor)
        val highDay = MacroPolicy.targetsFor(1700.0, anchor)
        assertEquals(lowDay.proteinG, highDay.proteinG, 0.0)
        assertEquals(lowDay.fatG, highDay.fatG, 0.0)
        assertEquals((1700.0 - 1243.0) / 4.0, highDay.carbsG - lowDay.carbsG, 1e-9)
        assertEquals(0.0, MacroPolicy.targetsFor(100.0, anchor).carbsG, 0.0)
    }
}
