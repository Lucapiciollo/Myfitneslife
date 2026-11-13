package com.myfitai.app.domain.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressAnalysisCompactContractTest {
    @Test
    fun parsesCompactAnalysis() {
        val json = """{"data":"PA1\nC|PR|H\nP|WA|F|H\nP|MU|F|M\nS|Trend complessivo coerente con ricomposizione positiva\nV|1|ok"}"""
        val result = ProgressAnalysisCompactContract.parse(json)
        assertEquals(ProgressAnalysisCompactContract.Classification.POSITIVE_RECOMPOSITION, result.classification)
        assertEquals(ProgressAnalysisCompactContract.Confidence.HIGH, result.confidence)
        assertEquals(2, result.patterns.size)
        assertTrue(result.valid)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownClassificationCode() {
        ProgressAnalysisCompactContract.parse("""{"data":"PA1\nC|ZZ|H\nS|Sintesi valida\nV|1|ok"}""")
    }

    @Test
    fun savedAnalysesWithTheLegacyTrainingPatternRemainReadable() {
        val result = ProgressAnalysisCompactContract.parse("""{"data":"PA1\nC|ST|M\nP|TR|X|L\nS|Dati stabili\nV|1|ok"}""")
        assertEquals(ProgressAnalysisCompactContract.PatternCode.TRAINING, result.patterns.single().code)
    }

    @Test
    fun promptNoLongerOffersTrainingOrWorkoutInputs() {
        val prompt = ProgressAnalysisService.SYSTEM_PROMPT
        assertTrue(!prompt.contains("TR training"))
        assertTrue(!prompt.contains("workout", ignoreCase = true))
        assertTrue(!ProgressAnalysisCompactContract.PROTOCOL.contains("_TR_"))
        assertTrue(prompt.contains("A=registeredDeviations|windowDays"))
    }
}
