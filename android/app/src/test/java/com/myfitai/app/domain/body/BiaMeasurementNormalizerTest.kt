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

    // Righe della foto sintetica provata su device (K7), nell'ordine in cui compaiono a schermo.
    private val photoRows = listOf(
        "Peso|82.4|kg",
        "Grasso corporeo|24.6|%",
        "Grasso viscerale|8|?",
        "Massa muscolare|58.9|kg",
        "Massa muscolo scheletrico|33.1|kg",
        "Acqua corporea|54.3|%",
        "BMR|1712|kcal",
        "Massa grassa|20.3|kg",
        "Massa magra|62.1|kg",
        "Acqua|44.7|kg",
        "Grasso sottocutaneo|18.9|%",
        "Massa ossea|3.3|kg",
        "Proteine|17.4|%",
        "Proteine|14.3|kg",
        "Eta corporea|46|anni",
        "BMI|26.8|?",
    )

    private fun normalizeRows(rows: List<String>): BiaMeasurementNormalizer.Result {
        val payload = "B2\nD|1|05/11/2026 07:45|?|H|\n" + rows.joinToString("\n") { "M|$it" }
        return BiaMeasurementNormalizer.normalize(BiaRawImportContract.parse(payload))
    }

    private fun assertPhotoValues(result: BiaMeasurementNormalizer.Result) {
        val p = result.preview
        assertEquals(82.4f, p.weightKg!!, 0.01f)
        assertEquals(24.6f, p.bodyFatPercent!!, 0.01f)
        assertEquals(8f, p.visceralFatLevel!!, 0.01f)
        assertEquals(58.9f, p.muscleMassKg!!, 0.01f)
        assertEquals(33.1f, p.skeletalMuscleKg!!, 0.01f)
        assertEquals(54.3f, p.bodyWaterPercent!!, 0.01f)
        assertEquals(1712f, p.bmrKcal!!, 0.01f)
        assertEquals(20.3f, p.fatMassKg!!, 0.01f)
        assertEquals(62.1f, p.leanMassKg!!, 0.01f)
        assertEquals(44.7f, p.bodyWaterKg!!, 0.01f)
        assertEquals(18.9f, p.subcutaneousFatPercent!!, 0.01f)
        assertEquals(3.3f, p.boneMassKg!!, 0.01f)
        assertEquals(17.4f, p.proteinPercent!!, 0.01f)
        assertEquals(14.3f, p.proteinKg!!, 0.01f)
        assertEquals(46f, p.bodyAgeYears!!, 0.01f)
        assertEquals(26.8f, p.bmi!!, 0.01f)
        assertTrue(result.derivedFields.isEmpty())
        assertTrue(p.notes, !p.notes.contains("Derivati"))
        assertTrue(p.warnings.toString(), p.warnings.isEmpty())
    }

    @Test
    fun deviceSyntheticPhoto_readsAllSixteenFieldsWithoutDerivedProvenance() {
        assertPhotoValues(normalizeRows(photoRows))
    }

    @Test
    fun deviceSyntheticPhoto_reversedOrderAndSegmentalRowsFirst() {
        val segmental = listOf(
            "Massa muscolare braccio destro|3.1|kg",
            "Massa muscolare gamba sinistra|9.8|kg",
            "Massa grassa tronco|10.2|kg",
            "Grasso corporeo braccio sinistro|20.1|%",
            "Peso osseo braccio destro|0.3|kg",
        )
        assertPhotoValues(normalizeRows(segmental + photoRows.reversed()))
    }

    @Test
    fun englishLabels_areMatchedForEveryField() {
        val result = normalizeRows(
            listOf(
                "Weight|82.4|kg",
                "Body Fat|24.6|%",
                "Visceral Fat|8|?",
                "Muscle Mass|58.9|kg",
                "Skeletal Muscle Mass|33.1|kg",
                "Body Water|54.3|%",
                "Basal Metabolic Rate|1712|kcal",
                "Fat Mass|20.3|kg",
                "Fat Free Mass|62.1|kg",
                "Body Water|44.7|kg",
                "Subcutaneous Fat|18.9|%",
                "Bone Mass|3.3|kg",
                "Protein|17.4|%",
                "Protein|14.3|kg",
                "Metabolic Age|46|years",
                "Body Mass Index|26.8|?",
            ),
        )
        assertPhotoValues(result)
    }

    @Test
    fun mixedItalianEnglishLabels_areMatchedForEveryField() {
        val result = normalizeRows(
            listOf(
                "Peso corporeo|82.4|kg",
                "Body Fat|24.6|%",
                "Grasso viscerale|8|?",
                "Muscle mass|58.9|kg",
                "Skeletal muscle|33.1|kg",
                "Acqua corporea|54.3|%",
                "Metabolismo basale|1712|kcal",
                "Fat mass|20.3|kg",
                "Massa magra|62.1|kg",
                "Total body water|44.7|kg",
                "Sottocutaneo|18.9|%",
                "Ossa|3.3|kg",
                "Proteine|17.4|%",
                "Protein mass|14.3|kg",
                "Eta metabolica|46|anni",
                "IMC|26.8|?",
            ),
        )
        assertPhotoValues(result)
    }

    @Test
    fun skeletalMuscleSynonyms_areAllMatched() {
        listOf(
            "Muscolo scheletrico", "Massa muscolo scheletrico", "Massa muscolare scheletrica",
            "Skeletal muscle", "Skeletal muscle mass", "SMM", "Massa muscolo scheletrico (kg)",
        ).forEach { label ->
            val result = normalizeRows(listOf("Peso|82.4|kg", "Massa muscolare|58.9|kg", "$label|33.1|kg"))
            assertEquals(label, 33.1f, result.preview.skeletalMuscleKg!!, 0.01f)
            assertEquals(label, 58.9f, result.preview.muscleMassKg!!, 0.01f)
        }
    }

    @Test
    fun explicitWaterKg_winsOverDerivedAndIsNotDeclaredDerived() {
        val result = normalizeRows(listOf("Peso|82.4|kg", "Acqua corporea|54.3|%", "Acqua|44.7|kg"))
        assertEquals(44.7f, result.preview.bodyWaterKg!!, 0.001f)
        assertTrue(result.derivedFields.isEmpty())
        assertTrue(result.preview.notes, !result.preview.notes.contains("Derivati"))
        assertTrue(result.preview.warnings.isEmpty())
    }

    @Test
    fun missingExplicitWaterKg_isDerivedAndDeclared() {
        val result = normalizeRows(listOf("Peso|80|kg", "Acqua corporea|60|%"))
        assertEquals(48f, result.preview.bodyWaterKg!!, 0.01f)
        assertEquals(setOf("bodyWaterKg"), result.derivedFields)
        assertTrue(result.preview.notes, result.preview.notes.contains("Derivati: acqua kg"))
    }

    @Test
    fun explicitValuesDivergingMoreThanFivePercent_keepExplicitAndWarn() {
        val result = normalizeRows(listOf("Peso|82.4|kg", "Acqua corporea|54.3|%", "Acqua|60|kg"))
        assertEquals(60f, result.preview.bodyWaterKg!!, 0.001f)
        assertEquals(54.3f, result.preview.bodyWaterPercent!!, 0.001f)
        assertTrue(result.derivedFields.isEmpty())
        assertEquals(result.preview.warnings.toString(), 1, result.preview.warnings.size)
        assertTrue(result.preview.warnings.single().contains("Acqua"))
    }

    @Test
    fun explicitValuesWithinFivePercent_doNotWarn() {
        val result = normalizeRows(listOf("Peso|82.4|kg", "Acqua corporea|54.3|%", "Acqua|46.5|kg"))
        assertTrue(result.preview.warnings.toString(), result.preview.warnings.isEmpty())
    }

    @Test
    fun explicitFatProteinAndLeanDivergence_eachWarnsOnce() {
        val result = normalizeRows(
            listOf(
                "Peso|80|kg", "Grasso corporeo|20|%", "Massa grassa|25|kg",
                "Proteine|16|%", "Proteine|18|kg", "Massa magra|50|kg",
            ),
        )
        assertEquals(result.preview.warnings.toString(), 3, result.preview.warnings.size)
        assertEquals(25f, result.preview.fatMassKg!!, 0.001f)
        assertEquals(18f, result.preview.proteinKg!!, 0.001f)
        assertEquals(50f, result.preview.leanMassKg!!, 0.001f)
        assertTrue(result.derivedFields.isEmpty())
    }

    @Test
    fun boneWeightAndFatFreeWeight_areNeverTakenAsBodyWeight() {
        val result = normalizeRows(listOf("Peso osseo|3.3|kg", "Peso corporeo senza grasso|62.1|kg", "Peso magro|61|kg"))
        assertEquals(null, result.preview.weightKg)
        assertEquals(3.3f, result.preview.boneMassKg!!, 0.01f)
        assertEquals(62.1f, result.preview.leanMassKg!!, 0.01f)
    }

    @Test
    fun segmentalRowsOnly_fillNoWholeBodyField() {
        val result = normalizeRows(
            listOf(
                "Massa muscolare braccio destro|3.1|kg", "Massa muscolo scheletrico gamba destra|9.8|kg",
                "Massa grassa tronco|10.2|kg", "Grasso corporeo braccio sinistro|20.1|%",
                "Acqua gamba sinistra|7|kg",
            ),
        )
        val p = result.preview
        assertEquals(null, p.muscleMassKg)
        assertEquals(null, p.skeletalMuscleKg)
        assertEquals(null, p.fatMassKg)
        assertEquals(null, p.bodyFatPercent)
        assertEquals(null, p.bodyWaterKg)
    }
}
