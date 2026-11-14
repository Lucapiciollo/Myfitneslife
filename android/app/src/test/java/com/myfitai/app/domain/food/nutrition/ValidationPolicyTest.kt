package com.myfitai.app.domain.food.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationPolicyTest {
    private val targets = DayTargets(kcal = 2000.0, proteinG = 150.0, carbsG = 220.0, fatG = 60.0)

    private fun totals(kcal: Double, p: Double = 150.0, c: Double = 220.0, f: Double = 60.0) = NutrientTotals(kcal, p, c, f)

    @Test
    fun kcalWithinFirstAttemptTolerance_isAccepted() {
        val v = ValidationPolicy.evaluate(totals(2060.0), targets, ValidationPolicy.FIRST_ATTEMPT_TOLERANCE)
        assertFalse(v.isBlocking)
        assertTrue(v.isAccepted)
        assertEquals(0.03, v.kcalDeviation, 1e-12)
    }

    @Test
    fun kcalBeyondFirstAttemptTolerance_blocks() {
        assertTrue(ValidationPolicy.evaluate(totals(2061.0), targets, ValidationPolicy.FIRST_ATTEMPT_TOLERANCE).isBlocking)
        assertTrue(ValidationPolicy.evaluate(totals(1939.0), targets, ValidationPolicy.FIRST_ATTEMPT_TOLERANCE).isBlocking)
    }

    @Test
    fun retryToleranceIsWider() {
        // +3,5%: bloccato al primo tentativo, accettato nei retry; +4,5% bloccato anche nei retry.
        assertTrue(ValidationPolicy.evaluate(totals(2070.0), targets, ValidationPolicy.FIRST_ATTEMPT_TOLERANCE).isBlocking)
        assertFalse(ValidationPolicy.evaluate(totals(2070.0), targets, ValidationPolicy.RETRY_TOLERANCE).isBlocking)
        assertTrue(ValidationPolicy.evaluate(totals(2090.0), targets, ValidationPolicy.RETRY_TOLERANCE).isBlocking)
    }

    @Test
    fun macroDeviationsNeverBlock_onlyWarn() {
        // Kcal perfette ma proteine -20%, carboidrati +30%, grassi -50%: nessun blocco, tre avvisi.
        val v = ValidationPolicy.evaluate(totals(2000.0, p = 120.0, c = 286.0, f = 30.0), targets, ValidationPolicy.FIRST_ATTEMPT_TOLERANCE)
        assertFalse(v.isBlocking)
        assertEquals(3, v.warnings.size)
        val protein = v.warnings.first { it.macro == ValidationPolicy.Macro.PROTEIN }
        assertEquals(-0.2, protein.deviation, 1e-12)
        assertEquals(-30.0, protein.deviationG, 1e-12)
        assertEquals(0.3, v.warnings.first { it.macro == ValidationPolicy.Macro.CARBS }.deviation, 1e-12)
        assertEquals(-0.5, v.warnings.first { it.macro == ValidationPolicy.Macro.FAT }.deviation, 1e-12)
    }

    @Test
    fun macrosWithinToleranceOrWithoutTarget_produceNoWarning() {
        val ok = ValidationPolicy.evaluate(totals(2000.0, p = 152.0, c = 218.0, f = 61.0), targets, 0.03)
        assertTrue(ok.warnings.isEmpty())
        val noMacroTargets = ValidationPolicy.evaluate(totals(2000.0, p = 10.0, c = 10.0, f = 10.0), DayTargets(2000.0), 0.03)
        assertTrue(noMacroTargets.warnings.isEmpty())
        assertFalse(noMacroTargets.isBlocking)
    }

    @Test
    fun kcalOnlyOverload_hasNoWarnings() {
        val v = ValidationPolicy.evaluate(totals(1900.0), 2000.0, 0.03)
        assertTrue(v.isBlocking)
        assertEquals(-0.05, v.kcalDeviation, 1e-12)
        assertTrue(v.warnings.isEmpty())
    }

    @Test
    fun invalidKcalTarget_blocks() {
        assertTrue(ValidationPolicy.evaluate(totals(100.0), 0.0, 0.03).isBlocking)
    }

    @Test
    fun evaluationUsesUnroundedTotals() {
        // 2060,4 kcal = +3,02%: bloccato anche se arrotondato a 2060 (+3,00%).
        assertTrue(ValidationPolicy.evaluate(totals(2060.4), targets, 0.03).isBlocking)
    }
}
