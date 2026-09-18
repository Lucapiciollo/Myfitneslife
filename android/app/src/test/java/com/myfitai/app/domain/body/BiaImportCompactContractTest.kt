package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiaImportCompactContractTest {
    @Test
    fun concatenatedHeaderAndRecord_areSeparatedBeforeStrictParsing() {
        val payload = "BIA1B|1|?|90.7|20.4|6|67.3|41.5|52.9|?|HIGH||"

        val preview = BiaImportCompactContract.parse(payload)

        assertEquals(90.7f, preview.weightKg)
        assertEquals(20.4f, preview.bodyFatPercent)
        assertEquals(6f, preview.visceralFatLevel)
    }

    @Test
    fun invalidOptionalTimestamp_doesNotRejectReadableBiaValues() {
        val payload = """
            BIA1
            B|1|17 Sep 2026 18:50|89|18.3|8.8|69.7|34.8|56.6|1410|HIGH||Foto leggibile
        """.trimIndent()

        val preview = BiaImportCompactContract.parse(payload)

        assertNull(preview.measuredAtEpochMillis)
        assertEquals(89f, preview.weightKg)
        assertEquals(18.3f, preview.bodyFatPercent)
    }
}
