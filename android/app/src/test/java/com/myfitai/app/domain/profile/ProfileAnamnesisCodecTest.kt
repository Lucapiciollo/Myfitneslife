package com.myfitai.app.domain.profile

import com.myfitai.app.domain.profile.ProfileAnamnesis.CycleStatus
import com.myfitai.app.domain.profile.ProfileAnamnesis.DesiredOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAnamnesisCodecTest {
    @Test
    fun roundTrip_preservesStructuredOptionalAnswers() {
        val expected = ProfileAnamnesis(
            usualWeightKg = 68.5f,
            profession = "Lavoro d'ufficio",
            averageDailySteps = 7_500,
            trainingType = "Corsa e pesi",
            trainingSessionsPerWeek = 3,
            dailyWaterMl = 1_800,
            cycleStatus = CycleStatus.REGULAR,
            contraceptivesUsed = true,
            contraceptiveDetails = "Anello",
            gynecologicalConditions = "",
            menopause = false,
            healthConditions = "",
            gastrointestinalSymptoms = "Gonfiore occasionale",
            bowelHabits = "Regolare",
            medications = listOf(AnamnesisDose("Farmaco A", "10 mg")),
            supplements = listOf(AnamnesisDose("Integratore B", "1 capsula")),
            recentExams = true,
            documents = listOf(AnamnesisDocument("doc-1", "esami.pdf", "application/pdf")),
            desiredOutcome = DesiredOutcome.HEALTH_EDUCATION,
            desiredTimeframe = "3 mesi",
        )

        val result = ProfileAnamnesisCodec.decode(ProfileAnamnesisCodec.encode(expected))

        assertEquals(ProfileAnamnesisDecodeResult.Valid(expected), result)
    }

    @Test
    fun decode_distinguishesAbsentInvalidAndUnsupportedVersions() {
        assertEquals(ProfileAnamnesisDecodeResult.Absent, ProfileAnamnesisCodec.decode(null))
        assertEquals(ProfileAnamnesisDecodeResult.Invalid, ProfileAnamnesisCodec.decode("{"))
        assertEquals(
            ProfileAnamnesisDecodeResult.UnsupportedVersion(2),
            ProfileAnamnesisCodec.decode("""{"schemaVersion":2}"""),
        )
    }

    @Test
    fun sensitiveValuesAreRedactedFromStringRepresentations() {
        val dose = AnamnesisDose("Farmaco riservato", "dose riservata")
        val document = AnamnesisDocument("id", "esame-riservato.pdf", "application/pdf")
        val anamnesis = ProfileAnamnesis(
            medications = listOf(dose),
            documents = listOf(document),
            healthConditions = "dato riservato",
        )

        assertFalse(anamnesis.toString().contains("dato riservato"))
        assertFalse(dose.toString().contains("Farmaco riservato"))
        assertFalse(document.toString().contains("esame-riservato.pdf"))
        assertTrue(anamnesis.toString().contains("redacted"))
    }
}