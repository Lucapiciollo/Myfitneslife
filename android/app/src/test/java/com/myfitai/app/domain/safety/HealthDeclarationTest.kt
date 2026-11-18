package com.myfitai.app.domain.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthDeclarationTest {
    private val canonicalJson =
        """{"schemaVersion":1,"policyVersion":"S2-A.1","pregnancyOrLactation":true,"clinicianManagedConditionOrMedication":false,"confirmedAtEpochMillis":1794700000000}"""

    @Test
    fun encodesDeterministicallyAndRoundTripsACompleteDeclaration() {
        val declaration = HealthDeclaration(
            pregnancyOrLactation = true,
            clinicianManagedConditionOrMedication = false,
            confirmedAtEpochMillis = 1_794_700_000_000L,
        )

        assertEquals(canonicalJson, HealthDeclarationCodec.encode(declaration))
        assertEquals(HealthDeclarationCodec.encode(declaration), HealthDeclarationCodec.encode(declaration))
        assertEquals(HealthDeclarationDecodeResult.Valid(declaration), HealthDeclarationCodec.decode(canonicalJson))
    }

    @Test
    fun nullAnswersAreIncompleteAndNeverConvertedToFalse() {
        val json = canonicalJson
            .replace("\"pregnancyOrLactation\":true", "\"pregnancyOrLactation\":null")
            .replace("\"clinicianManagedConditionOrMedication\":false", "\"clinicianManagedConditionOrMedication\":null")
        val expected = HealthDeclaration(
            pregnancyOrLactation = null,
            clinicianManagedConditionOrMedication = null,
            confirmedAtEpochMillis = 1_794_700_000_000L,
        )

        assertEquals(HealthDeclarationDecodeResult.Incomplete(expected), HealthDeclarationCodec.decode(json))
        assertEquals(json, HealthDeclarationCodec.encode(expected))
    }

    @Test
    fun missingAnswerFieldIsInvalidRatherThanAnUnansweredValue() {
        val missing = canonicalJson.replace(",\"pregnancyOrLactation\":true", "")

        assertEquals(
            HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.UNEXPECTED_FIELDS),
            HealthDeclarationCodec.decode(missing),
        )
    }

    @Test
    fun distinguishesAbsentFromMalformedJson() {
        assertEquals(HealthDeclarationDecodeResult.Absent, HealthDeclarationCodec.decode(null))
        assertEquals(
            HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.MALFORMED_JSON),
            HealthDeclarationCodec.decode(" "),
        )
        assertEquals(
            HealthDeclarationDecodeResult.Invalid(HealthDeclarationDecodeResult.InvalidReason.MALFORMED_JSON),
            HealthDeclarationCodec.decode("{\"schemaVersion\":"),
        )
    }

    @Test
    fun rejectsMissingExtraAndWronglyTypedFields() {
        val cases = listOf(
            canonicalJson.replace(",\"confirmedAtEpochMillis\":1794700000000", ""),
            canonicalJson.replace("}", ",\"unexpected\":true}"),
            canonicalJson.replace("\"pregnancyOrLactation\":true", "\"pregnancyOrLactation\":\"true\""),
            canonicalJson.replace("\"confirmedAtEpochMillis\":1794700000000", "\"confirmedAtEpochMillis\":1.5"),
        )

        cases.forEach { json ->
            assertTrue("Should reject $json", HealthDeclarationCodec.decode(json) is HealthDeclarationDecodeResult.Invalid)
        }
    }

    @Test
    fun distinguishesUnsupportedSchemaAndPolicyVersions() {
        val unknownSchema = canonicalJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        val unknownPolicy = canonicalJson.replace("\"policyVersion\":\"S2-A.1\"", "\"policyVersion\":\"S2-A.2\"")

        assertEquals(
            HealthDeclarationDecodeResult.UnsupportedVersion(2, HealthDeclaration.CURRENT_POLICY_VERSION),
            HealthDeclarationCodec.decode(unknownSchema),
        )
        assertEquals(
            HealthDeclarationDecodeResult.UnsupportedVersion(HealthDeclaration.CURRENT_SCHEMA_VERSION, "S2-A.2"),
            HealthDeclarationCodec.decode(unknownPolicy),
        )
    }

    @Test
    fun declarationStringRepresentationRedactsBothAnswers() {
        val declaration = HealthDeclaration(
            pregnancyOrLactation = true,
            clinicianManagedConditionOrMedication = false,
            confirmedAtEpochMillis = 1_794_700_000_000L,
        )

        val rendered = declaration.toString()
        assertFalse(rendered.contains("true"))
        assertFalse(rendered.contains("false"))
        assertEquals("HealthDeclaration(<redacted>)", rendered)
    }

    @Test
    fun encoderRejectsUnsupportedVersionsAndInvalidConfirmationDate() {
        val declaration = HealthDeclaration(null, false, confirmedAtEpochMillis = 1L)

        assertTrue(runCatching { HealthDeclarationCodec.encode(declaration.copy(schemaVersion = 2)) }.isFailure)
        assertTrue(runCatching { HealthDeclarationCodec.encode(declaration.copy(policyVersion = "S2-A.2")) }.isFailure)
        assertTrue(runCatching { HealthDeclarationCodec.encode(declaration.copy(confirmedAtEpochMillis = 0L)) }.isFailure)
    }
}
