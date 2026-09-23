package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaImportContractTest {
    @Test
    fun nonBiaDocument_isValidRejectionWithNoPreviewValues() {
        val preview = BiaImportContract.parse("""
            {"isBiaDocument":false,"rejectionReason":"La foto non contiene una rilevazione BIA.","confidence":"LOW","notes":"Immagine non pertinente."}
        """.trimIndent())
        assertTrue(BiaImportContract.validate(preview).isSuccess)
        assertEquals(false, preview.isBiaDocument)
        assertTrue(preview.weightKg == null)
    }

    @Test
    fun biaDocument_requiresAtLeastOnePositiveMeasurement() {
        val preview = BiaImportContract.parse("""
            {"isBiaDocument":true,"rejectionReason":"","confidence":"HIGH","notes":"Valori leggibili.","weightKg":87.8,"bodyFatPercent":18.0}
        """.trimIndent())
        assertTrue(BiaImportContract.validate(preview).isSuccess)
    }

    @Test
    fun extendedMetrics_arePreservedAndValidated() {
        val preview = BiaImportContract.parse("""
            {"isBiaDocument":true,"rejectionReason":"","confidence":"HIGH","notes":"Fitdays","fatMassKg":18.5,"leanMassKg":72.1,"bodyWaterKg":52.9,"subcutaneousFatPercent":14.6,"boneMassKg":4.8,"proteinPercent":15.9,"proteinKg":14.4,"bodyAgeYears":42,"bmi":26.5}
        """.trimIndent())

        assertTrue(BiaImportContract.validate(preview).isSuccess)
        assertEquals(72.1f, preview.leanMassKg!!, 0.01f)
        assertEquals(52.9f, preview.bodyWaterKg!!, 0.01f)
        assertEquals(15.9f, preview.proteinPercent!!, 0.01f)
    }
}
