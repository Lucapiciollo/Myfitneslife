package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaRawImportMatchingTest {
    private fun normalize(rows: String, source: String = "Fitdays") =
        BiaMeasurementNormalizer.normalize(BiaRawImportContract.parse("B2\nD|1|?|$source|H|\n" + rows.trimIndent()))

    @Test
    fun boneWeightRow_isNotTakenAsBodyWeight_whateverTheRowOrder() {
        val ordered = normalize("""
            M|Peso osseo|3.2|kg
            M|Peso|80.5|kg
        """)
        val reversed = normalize("""
            M|Peso|80.5|kg
            M|Peso osseo|3.2|kg
        """)
        listOf(ordered, reversed).forEach { result ->
            assertEquals(80.5f, result.preview.weightKg!!, 0.001f)
            assertEquals(3.2f, result.preview.boneMassKg!!, 0.001f)
        }
    }

    @Test
    fun onlyBoneWeightPresent_leavesBodyWeightEmpty() {
        val result = normalize("M|Peso osseo|3.2|kg")
        assertNull(result.preview.weightKg)
        assertEquals(3.2f, result.preview.boneMassKg!!, 0.001f)
    }

    @Test
    fun segmentalRows_neverFillWholeBodyFields() {
        val result = normalize("""
            M|Massa muscolare braccio destro|3.4|kg
            M|Massa grassa braccio sinistro|1.1|kg
            M|Massa muscolare|61.0|kg
            M|Peso|80.5|kg
        """)
        assertEquals(61.0f, result.preview.muscleMassKg!!, 0.001f)
        assertNull(result.preview.fatMassKg)
        assertNull(result.preview.skeletalMuscleKg)
    }

    @Test
    fun skeletalMuscleRow_isNotTakenAsGenericMuscleMass() {
        val result = normalize("""
            M|Massa muscolare scheletrica|41.5|kg
            M|Peso|80.5|kg
        """)
        assertNull(result.preview.muscleMassKg)
        assertEquals(41.5f, result.preview.skeletalMuscleKg!!, 0.001f)
    }

    @Test
    fun labelsWithTrailingUnitOrPunctuation_stillMatch() {
        val result = normalize("""
            M|Peso corporeo (kg)|80.5|kg
            M|Massa Grassa:|16.1|kg
        """)
        assertEquals(80.5f, result.preview.weightKg!!, 0.001f)
        assertEquals(16.1f, result.preview.fatMassKg!!, 0.001f)
    }

    @Test
    fun derivedFields_areDescribedInNotesWithTheirFormula() {
        val result = normalize("""
            M|Peso|80|kg
            M|Massa grassa|16|kg
        """)
        assertTrue(result.derivedFields.contains("bodyFatPercent"))
        val notes = result.preview.notes
        assertTrue(notes, notes.contains("Import foto"))
        assertTrue(notes, notes.contains("grasso% da massa grassa/peso"))
        assertTrue(notes, notes.contains("Fitdays"))
    }

    @Test
    fun impossibleValues_becomeNullWithAWarning_insteadOfBeingSaved() {
        val result = normalize("""
            M|Peso|805|kg
            M|Tasso di grasso corporeo|75|%
            M|Massa ossea|3.2|kg
        """)
        assertNull(result.preview.weightKg)
        assertNull(result.preview.bodyFatPercent)
        assertEquals(3.2f, result.preview.boneMassKg!!, 0.001f)
        val warnings = result.preview.warnings.joinToString(" ")
        assertTrue(warnings, warnings.contains("Peso") && warnings.contains("Grasso corporeo"))
        assertFalse(result.preview.warnings.isEmpty())
    }

    @Test
    fun impossibleWeight_doesNotFeedDerivedValues() {
        val result = normalize("""
            M|Peso|805|kg
            M|Massa grassa|16|kg
        """)
        assertNull(result.preview.bodyFatPercent)
        assertNull(result.preview.leanMassKg)
    }
}
