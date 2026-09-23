package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaMeasurementNormalizerTest {
    @Test
    fun fitdaysReport_mapsEverySupportedVisibleMetric() {
        val document = BiaRawImportContract.parse(
            """
            B2
            D|1|12/09/2026 09:59:35|Fitdays|H|
            M|Peso|90.7|kg
            M|massa grassa|18.5|kg
            M|Tasso di grasso corporeo|20.4|%
            M|Massa ossea|4.8|kg
            M|Quantità di proteine|14.4|kg
            M|Quantità di proteine|15.9|%
            M|Contenuto d'acqua|52.9|kg
            M|Contenuto d'acqua|58.3|%
            M|Massa muscolare|67.3|kg
            M|Muscolo scheletrico|41.5|kg
            M|Grado di grasso viscerale|6|?
            M|Tasso metabolico basale|1929|kcal
            M|Peso corporeo senza grasso|72.1|kg
            M|grasso sottocutaneo|14.6|%
            M|BMI|26.5|kg/m2
            M|Età corporea|42|anni
            """.trimIndent()
        )

        val result = BiaMeasurementNormalizer.normalize(document)
        val preview = result.preview

        assertEquals(90.7f, preview.weightKg!!, 0.01f)
        assertEquals(20.4f, preview.bodyFatPercent!!, 0.01f)
        assertEquals(18.5f, preview.fatMassKg!!, 0.01f)
        assertEquals(72.1f, preview.leanMassKg!!, 0.01f)
        assertEquals(58.3f, preview.bodyWaterPercent!!, 0.01f)
        assertEquals(52.9f, preview.bodyWaterKg!!, 0.01f)
        assertEquals(15.9f, preview.proteinPercent!!, 0.01f)
        assertEquals(14.4f, preview.proteinKg!!, 0.01f)
        assertEquals(4.8f, preview.boneMassKg!!, 0.01f)
        assertEquals(14.6f, preview.subcutaneousFatPercent!!, 0.01f)
        assertEquals(67.3f, preview.muscleMassKg!!, 0.01f)
        assertEquals(41.5f, preview.skeletalMuscleKg!!, 0.01f)
        assertEquals(6f, preview.visceralFatLevel!!, 0.01f)
        assertEquals(1929f, preview.bmrKcal!!, 0.01f)
        assertEquals(42f, preview.bodyAgeYears!!, 0.01f)
        assertEquals(26.5f, preview.bmi!!, 0.01f)
        assertTrue(result.derivedFields.isEmpty())
        assertEquals("Fitdays", result.source)
    }

    @Test
    fun genericEnglishReport_mapsAliasesAndDerivesEquivalentMasses() {
        val document = BiaRawImportContract.parse(
            """
            B2
            D|1|2026-09-12 09:59|Generic scale|M|
            M|Body weight|80|kg
            M|Body fat percentage|20|percent
            M|Total body water|60|percentage
            M|Protein rate|16|percent
            M|Bone mass|3.5|kg
            M|Metabolic age|40|years
            """.trimIndent()
        )

        val result = BiaMeasurementNormalizer.normalize(document)

        assertEquals(16f, result.preview.fatMassKg!!, 0.01f)
        assertEquals(64f, result.preview.leanMassKg!!, 0.01f)
        assertEquals(48f, result.preview.bodyWaterKg!!, 0.01f)
        assertEquals(12.8f, result.preview.proteinKg!!, 0.01f)
        assertTrue(result.derivedFields.containsAll(listOf("fatMassKg", "leanMassKg", "bodyWaterKg", "proteinKg")))
    }
}
