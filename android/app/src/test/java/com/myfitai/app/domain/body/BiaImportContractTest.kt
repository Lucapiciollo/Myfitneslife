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
}
