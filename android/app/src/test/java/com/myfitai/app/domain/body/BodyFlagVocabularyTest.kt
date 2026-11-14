package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Linguaggio prudente: codici, chiavi, motivi e chiavi di evidenza non devono contenere termini diagnostici. */
class BodyFlagVocabularyTest {
    private val forbidden = BodyFlagVocabulary.STEMS

    private fun assertClean(label: String, text: String) {
        val lower = text.lowercase()
        forbidden.forEach { term -> assertFalse("$label contiene il termine vietato '$term': $text", lower.contains(term)) }
    }

    @Test
    fun codesAndMessageKeys_haveNoDiagnosticTerms_andAreWellFormed() {
        BodyFlagCode.entries.forEach {
            assertClean("codice", it.name)
            assertClean("chiave", it.messageKey)
            assertTrue(it.messageKey.matches(Regex("body_flag_[a-z0-9_]+")))
        }
        assertEquals(BodyFlagCode.entries.size, BodyFlagCode.entries.map { it.messageKey }.toSet().size)
    }

    @Test
    fun skipReasonsAndRules_haveNoDiagnosticTerms() {
        BodyFlagSkipReason.entries.forEach { assertClean("motivo", it.name) }
        BodyFlagRule.entries.forEach { assertClean("regola", it.name) }
    }

    @Test
    fun evidenceKeysOfEveryProducedFlag_haveNoDiagnosticTerms() {
        val sample = listOf(
            FlagFixtures.eval(
                FlagFixtures.profile(age = 62),
                bia = listOf(
                    FlagFixtures.bia(56, weight = 80f, lean = 60f, skeletal = 24f, visceral = 10f, fat = 25f),
                    FlagFixtures.bia(28, weight = 77f, lean = 58.5f, skeletal = 23.2f, visceral = 11f, fat = 24f),
                    FlagFixtures.bia(0, weight = 74f, lean = 57.5f, skeletal = 22.2f, visceral = 12f, fat = 22.5f),
                ),
                body = listOf(FlagFixtures.body(3, waist = 108f, weight = 120f)),
            ),
        )
        val keys = sample.flatMap { it.flags }.flatMap { flag -> flag.evidence.map { it.key } + flag.messageKey }
        assertTrue(keys.isNotEmpty())
        keys.forEach { assertClean("evidenza", it) }
    }

    @Test
    fun skeletalMuscleFlag_keyInvitesFunctionalTest_andNeverNamesTheCondition() {
        assertEquals("body_flag_skeletal_muscle_decline_check_strength", BodyFlagCode.SKELETAL_MUSCLE_DECLINE.messageKey)
        assertFalse(BodyFlagCode.SKELETAL_MUSCLE_DECLINE.messageKey.contains("sarcopen"))
    }

    @Test
    fun unverifiedSources_areDeclaredAndNeverReferencedByFlagCodes() {
        val unverified = BodyFlagThresholds.SOURCES.values.filter { it.verification == SourceVerification.UNVERIFIED_NOT_USED }.map { it.id }
        assertTrue(BodyFlagThresholds.SRC_FITDAYS_BANDS in unverified)
        assertTrue(BodyFlagThresholds.SRC_BODY_FAT_AGE_TABLES in unverified)
        // Ogni fonte ha affidabilita e applicabilita dichiarate.
        BodyFlagThresholds.SOURCES.values.forEach {
            assertTrue(it.applicability.isNotBlank())
            assertTrue(it.body.isNotBlank() && it.document.isNotBlank())
        }
    }
}
