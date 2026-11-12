package com.myfitai.app.domain.calculation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Golden dei profili D e U dell'audit calcoli. Se una formula cambia, questi numeri cambiano e vanno
 * rivisti deliberatamente (non aggiornati a occhio).
 *
 * Nota: il punto (7) dell'audit (tetto proteico / minimo carboidrati) NON e implementato in questa
 * tranche; i carboidrati qui sono quelli del motore attuale (2,0 g/kg proteine).
 */
class CalculationGoldenProfilesTest {

    @Test
    fun profileD_woman62_164cm_78kg_weightLoss() {
        val r = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 78.0,
                heightCm = 164.0,
                ageYears = 62,
                biologicalSex = LocalCalculationEngine.BiologicalSex.FEMALE,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
            )
        )
        assertEquals("MIFFLIN_ST_JEOR", r.bmrMethod)
        assertEquals(1334.0, r.bmrKcal!!, 0.05)
        assertEquals(2067.7, r.tdeeKcal!!, 0.05)
        assertEquals(1757.5, r.targetKcal!!, 0.1)
        assertEquals(143.0, r.carbsG!!, 0.1)
        assertFalse(r.floorApplied)
    }

    @Test
    fun profileU_man40_180cm_89_5kg_weightLoss() {
        val r = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 89.5,
                heightCm = 180.0,
                ageYears = 40,
                biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
            )
        )
        assertEquals("MIFFLIN_ST_JEOR", r.bmrMethod)
        assertEquals(1825.0, r.bmrKcal!!, 0.05)
        assertEquals(2828.8, r.tdeeKcal!!, 0.06)
        assertEquals(2404.4, r.targetKcal!!, 0.1)
        assertEquals(261.0, r.carbsG!!, 0.1)
        assertFalse(r.floorApplied)
    }
}
