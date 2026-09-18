package com.myfitai.app.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDataQualityAnalyzerTest {
    @Test
    fun normalizesOriginAndNutritionFields() {
        val origin = ExportDataQualityAnalyzer.sourceFrom("QA seed 20260914")
        assertEquals(ExportDataSource.QA, origin.dataSource)
        assertTrue(ExportDataQualityAnalyzer.normalizeWeightState("secco") == "DRY")
        assertTrue(ExportDataQualityAnalyzer.normalizeCategory("verdura") == "VEGETABLE")
        assertEquals(0.9, ExportDataQualityAnalyzer.nutritionConfidence("HIGH"))
    }
}
