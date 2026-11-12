package com.myfitai.app.domain.body

import com.myfitai.app.domain.body.BodyValueRanges.BiaField
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaFlowPresentationTest {

    @Test
    fun confidence_isMappedToThreeLevels() {
        assertEquals(BiaFlowPresentation.Confidence.HIGH, BiaFlowPresentation.confidence("HIGH"))
        assertEquals(BiaFlowPresentation.Confidence.MEDIUM, BiaFlowPresentation.confidence(" medium "))
        assertEquals(BiaFlowPresentation.Confidence.LOW, BiaFlowPresentation.confidence("LOW"))
        assertEquals(BiaFlowPresentation.Confidence.UNKNOWN, BiaFlowPresentation.confidence("boh"))
        assertEquals(BiaFlowPresentation.Confidence.UNKNOWN, BiaFlowPresentation.confidence(null))
    }

    @Test
    fun fieldsToDoubleCheck_onlyWithLowConfidence_andOnlyFilledFields() {
        val values = mapOf(BiaField.WEIGHT to 80f, BiaField.BODY_FAT to null, BiaField.BMI_FIELD to 24f)
        assertEquals(setOf(BiaField.WEIGHT, BiaField.BMI_FIELD), BiaFlowPresentation.fieldsToDoubleCheck(BiaFlowPresentation.Confidence.LOW, values))
        assertTrue(BiaFlowPresentation.fieldsToDoubleCheck(BiaFlowPresentation.Confidence.HIGH, values).isEmpty())
        assertTrue(BiaFlowPresentation.fieldsToDoubleCheck(BiaFlowPresentation.Confidence.MEDIUM, values).isEmpty())
    }

    @Test
    fun jobKeyFromTags_readsTheTimestampAfterJobMarker() {
        val tags = setOf("com.myfitai.AiJobWorker", "ai-bia_import-profile-3", "ai-bia_import-profile-3-job-1731400000000")
        assertEquals("1731400000000", BiaFlowPresentation.jobKeyFromTags(tags))
        assertNull(BiaFlowPresentation.jobKeyFromTags(setOf("ai-bia_import-profile-3")))
    }

    @Test
    fun notesForSave_keepsProvenanceAndAddsWarnings() {
        assertEquals("Import foto. Sorgente: Fitdays.", BiaFlowPresentation.notesForSave(" Import foto. Sorgente: Fitdays. ", emptyList()))
        assertEquals(
            "Import foto. Avvisi di lettura: Peso: 785 kg non è plausibile. Valore ignorato.",
            BiaFlowPresentation.notesForSave("Import foto.", listOf("Peso: 785 kg non è plausibile. Valore ignorato.")),
        )
        assertNull(BiaFlowPresentation.notesForSave("  ", emptyList()))
        assertNull(BiaFlowPresentation.notesForSave(null, emptyList()))
        assertEquals(2_000, BiaFlowPresentation.notesForSave("x".repeat(5_000), emptyList())!!.length)
    }

    @Test
    fun analysisError_stripsTheJobPrefix() {
        assertEquals("AUTH_FAILED", BiaFlowPresentation.analysisError("BIA_PROGRESS_COACH_FAILED:AUTH_FAILED"))
        assertEquals("Analisi BIA non disponibile", BiaFlowPresentation.analysisError("BIA_PROGRESS_COACH_FAILED:Analisi BIA non disponibile"))
        assertNull(BiaFlowPresentation.analysisError("BIA_PROGRESS_COACH_FAILED:"))
        assertNull(BiaFlowPresentation.analysisError(null))
    }

    @Test
    fun isNotBiaPhoto_matchesTheFixedHandlerText() {
        assertTrue(BiaFlowPresentation.isNotBiaPhoto("La foto non sembra una rilevazione BIA."))
        assertFalse(BiaFlowPresentation.isNotBiaPhoto("Import BIA non riuscito"))
    }

    @Test
    fun parseImportPayload_readsValuesNotesAndOptionalWarnings() {
        val payload = JSONObject()
            .put("weightKg", 76.8).put("bodyFatPercent", JSONObject.NULL)
            .put("measuredAtEpochMillis", 1_731_400_000_000L)
            .put("confidence", "LOW").put("notes", "Import foto. Sorgente: Fitdays.")
            .put("provider", "GEMINI").put("model", "m1")
            .put("warnings", JSONArray().put("Peso ignorato"))
        val result = BiaFlowPresentation.parseImportPayload(payload.toString())
        assertEquals(76.8f, result.preview.weightKg!!, 0.001f)
        assertNull(result.preview.bodyFatPercent)
        assertEquals(1_731_400_000_000L, result.preview.measuredAtEpochMillis)
        assertEquals("LOW", result.preview.confidence)
        assertEquals(listOf("Peso ignorato"), result.preview.warnings)
        assertEquals("GEMINI", result.provider)
        assertEquals("m1", result.model)
        assertEquals(76.8f, BiaFlowPresentation.importValues(result.preview)[BiaField.WEIGHT])
    }

    @Test
    fun parseImportPayload_withoutWarningsOrDate_isStillValid() {
        val payload = JSONObject().put("confidence", "HIGH").put("notes", "").put("provider", "OPENAI").put("model", "m")
        val result = BiaFlowPresentation.parseImportPayload(payload.toString())
        assertTrue(result.preview.warnings.isEmpty())
        assertNull(result.preview.measuredAtEpochMillis)
    }
}
