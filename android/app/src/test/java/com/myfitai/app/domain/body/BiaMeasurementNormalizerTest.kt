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

    // Foto reale Fitdays (K8): i valori in kg non devono mai finire nei campi %, e viceversa.
    private val fitdaysRealRows = listOf(
        "Peso|89.5|kg",
        "Grasso|19.9|%",
        "Grasso viscerale|6|?",
        "Massa muscolare|66.8|kg",
        "Muscolo scheletrico|41.2|kg",
        "Acqua|58.7|%",
        "BMR|1918|kcal",
        "Massa grassa|17.8|kg",
        "Massa magra|71.6|kg",
        "Acqua|52.5|kg",
        "Grasso sottocutaneo|14.2|%",
        "Massa ossea|4.8|kg",
        "Proteine|16|%",
        "Proteine|14.3|kg",
        "Eta corporea|42|anni",
        "BMI|26.2|?",
    )

    private fun assertFitdaysReal(result: BiaMeasurementNormalizer.Result, derived: Set<String> = emptySet()) {
        val p = result.preview
        assertEquals(89.5f, p.weightKg!!, 0.01f)
        assertEquals(19.9f, p.bodyFatPercent!!, 0.01f)
        assertEquals(6f, p.visceralFatLevel!!, 0.01f)
        assertEquals(66.8f, p.muscleMassKg!!, 0.01f)
        assertEquals(41.2f, p.skeletalMuscleKg!!, 0.01f)
        assertEquals(58.7f, p.bodyWaterPercent!!, 0.1f)
        assertEquals(1918f, p.bmrKcal!!, 0.01f)
        assertEquals(17.8f, p.fatMassKg!!, 0.01f)
        assertEquals(71.6f, p.leanMassKg!!, 0.01f)
        assertEquals(52.5f, p.bodyWaterKg!!, 0.01f)
        assertEquals(14.2f, p.subcutaneousFatPercent!!, 0.01f)
        assertEquals(4.8f, p.boneMassKg!!, 0.01f)
        assertEquals(16f, p.proteinPercent!!, 0.1f)
        assertEquals(14.3f, p.proteinKg!!, 0.01f)
        assertEquals(42f, p.bodyAgeYears!!, 0.01f)
        assertEquals(26.2f, p.bmi!!, 0.01f)
        assertEquals(derived, result.derivedFields)
    }

    @Test
    fun fitdaysReal_percentAndKgBothPresent_neverCrossed() {
        val result = normalizeRows(fitdaysRealRows)
        assertFitdaysReal(result)
        assertTrue(result.preview.warnings.toString(), result.preview.warnings.isEmpty())
        assertEquals(58.7f, result.preview.bodyWaterPercent!!, 0.001f)
        assertEquals(16f, result.preview.proteinPercent!!, 0.001f)
    }

    @Test
    fun fitdaysReal_kgRowsBeforePercentRows_stillNeverCrossed() {
        val kgFirst = fitdaysRealRows.filter { it.endsWith("|kg") } + fitdaysRealRows.filterNot { it.endsWith("|kg") }
        val result = normalizeRows(kgFirst)
        assertFitdaysReal(result)
        assertTrue(result.preview.warnings.toString(), result.preview.warnings.isEmpty())
    }

    @Test
    fun fitdaysReal_onlyKgWithGenericLabels_derivesPercentNeverAssignsKgToPercent() {
        val rows = fitdaysRealRows.filterNot { it == "Acqua|58.7|%" || it == "Proteine|16|%" }
        val result = normalizeRows(rows)
        val p = result.preview
        assertEquals(52.5f, p.bodyWaterKg!!, 0.001f)
        assertEquals(14.3f, p.proteinKg!!, 0.001f)
        assertEquals(58.7f, p.bodyWaterPercent!!, 0.1f)
        assertEquals(16f, p.proteinPercent!!, 0.1f)
        assertEquals(setOf("bodyWaterPercent", "proteinPercent"), result.derivedFields)
    }

    @Test
    fun fitdaysReal_onlyPercentPresent_derivesKgNeverOverwritingPercent() {
        val rows = fitdaysRealRows.filterNot { it == "Acqua|52.5|kg" || it == "Proteine|14.3|kg" }
        val result = normalizeRows(rows)
        val p = result.preview
        assertEquals(58.7f, p.bodyWaterPercent!!, 0.001f)
        assertEquals(16f, p.proteinPercent!!, 0.001f)
        assertEquals(52.5f, p.bodyWaterKg!!, 0.1f)
        assertEquals(14.3f, p.proteinKg!!, 0.1f)
        assertEquals(setOf("bodyWaterKg", "proteinKg"), result.derivedFields)
    }

    @Test
    fun missingUnit_isAssignedToTheFieldThatMatchesTheWeight() {
        val kgMissing = normalizeRows(listOf("Peso|89.5|kg", "Acqua|58.7|%", "Acqua|52.5|?", "Proteine|16|%", "Proteine|14.3|?"))
        assertEquals(58.7f, kgMissing.preview.bodyWaterPercent!!, 0.001f)
        assertEquals(52.5f, kgMissing.preview.bodyWaterKg!!, 0.001f)
        assertEquals(14.3f, kgMissing.preview.proteinKg!!, 0.001f)
        assertTrue(kgMissing.derivedFields.isEmpty())
        val percentMissing = normalizeRows(listOf("Peso|89.5|kg", "Acqua|58.7|", "Acqua|52.5|kg"))
        assertEquals(58.7f, percentMissing.preview.bodyWaterPercent!!, 0.001f)
        assertEquals(52.5f, percentMissing.preview.bodyWaterKg!!, 0.001f)
    }

    @Test
    fun missingUnit_ambiguousValueIsNotAssignedAndWarns() {
        val result = normalizeRows(listOf("Peso|89.5|kg", "Acqua|52.5|?", "Proteine|14.3|?"))
        val p = result.preview
        assertEquals(null, p.bodyWaterPercent)
        assertEquals(null, p.bodyWaterKg)
        assertEquals(null, p.proteinPercent)
        assertEquals(null, p.proteinKg)
        assertTrue(result.derivedFields.isEmpty())
        assertEquals(p.warnings.toString(), 2, p.warnings.count { it.contains("ambiguo") })
    }

    @Test
    fun missingUnit_plausibilityPicksTheOnlyPossibleField() {
        val kg = normalizeRows(listOf("Peso|89.5|kg", "Acqua|30|?"))
        assertEquals(30f, kg.preview.bodyWaterKg!!, 0.001f)
        val percent = normalizeRows(listOf("Peso|89.5|kg", "Acqua|72|?"))
        assertEquals(72f, percent.preview.bodyWaterPercent!!, 0.001f)
        assertEquals(setOf("bodyWaterKg"), percent.derivedFields)
    }

    @Test
    fun mislabelledUnits_bothRowsTaggedPercent_areRepairedWithTheWeightAndWarn() {
        val kgFirst = normalizeRows(listOf("Peso|89.5|kg", "Acqua|52.5|%", "Acqua|58.7|%", "Proteine|14.3|%", "Proteine|16|%"))
        val pctFirst = normalizeRows(listOf("Peso|89.5|kg", "Acqua|58.7|%", "Acqua|52.5|%", "Proteine|16|%", "Proteine|14.3|%"))
        listOf(kgFirst, pctFirst).forEach { result ->
            val p = result.preview
            assertEquals(58.7f, p.bodyWaterPercent!!, 0.001f)
            assertEquals(52.5f, p.bodyWaterKg!!, 0.001f)
            assertEquals(16f, p.proteinPercent!!, 0.001f)
            assertEquals(14.3f, p.proteinKg!!, 0.001f)
            assertTrue(result.derivedFields.isEmpty())
        }
        assertTrue(kgFirst.preview.warnings.toString(), kgFirst.preview.warnings.any { it.contains("Acqua corporea") })
    }

    @Test
    fun unitWrittenInLabel_isUsedAndConflictsFallBackToWeightCheck() {
        val labelUnit = normalizeRows(listOf("Peso|89.5|kg", "Acqua corporea (%)|58.7|?", "Acqua corporea (kg)|52.5|?"))
        assertEquals(58.7f, labelUnit.preview.bodyWaterPercent!!, 0.001f)
        assertEquals(52.5f, labelUnit.preview.bodyWaterKg!!, 0.001f)
        val conflict = normalizeRows(listOf("Peso|89.5|kg", "Acqua|58.7|%", "Acqua (kg)|52.5|%"))
        assertEquals(58.7f, conflict.preview.bodyWaterPercent!!, 0.001f)
        assertEquals(52.5f, conflict.preview.bodyWaterKg!!, 0.001f)
    }

    @Test
    fun kgOnlyFields_neverTakePercentRows() {
        val result = normalizeRows(listOf("Peso|89.5|kg", "Muscolo|45|%", "Massa ossea|5|%", "Massa magra|78|%"))
        assertEquals(null, result.preview.muscleMassKg)
        assertEquals(null, result.preview.boneMassKg)
        assertEquals(null, result.preview.leanMassKg)
    }

    @Test
    fun incoherentMasses_emitWarnings() {
        val result = normalizeRows(listOf("Peso|80|kg", "Massa magra|50|kg", "Acqua|60|kg", "Massa muscolare|40|kg", "Muscolo scheletrico|45|kg"))
        val w = result.preview.warnings
        assertTrue(w.toString(), w.any { it.contains("Acqua") && it.contains("supera la massa magra") })
        assertTrue(w.toString(), w.any { it.contains("Muscolo scheletrico") && it.contains("supera la massa muscolare") })
        val sum = normalizeRows(listOf("Peso|80|kg", "Massa grassa|40|kg", "Massa magra|20|kg"))
        assertTrue(sum.preview.warnings.toString(), sum.preview.warnings.any { it.contains("non coincide con il peso") })
    }
}