package com.myfitai.app.domain.food

import com.myfitai.app.data.local.entity.UserProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanPrerequisitesTest {
    private val today = LocalDate.of(2026, 11, 12)

    private fun profile(
        birth: Long? = today.minusYears(30).toEpochDay(),
        sex: String? = "Maschio",
        height: Float? = 175f,
    ) = UserProfileEntity(
        name = "Test", birthDateEpochDay = birth, heightCm = height, currentWeightKg = 70f,
        goal = "Mantenimento", activityLevel = "Moderata", wakeTimeMinutes = null, sleepTimeMinutes = null,
        dietaryPreferencesJson = null, createdAtEpochMillis = 0, updatedAtEpochMillis = 0, biologicalSex = sex,
    )

    @Test
    fun completeProfile_hasNoProblems() {
        assertTrue(PlanPrerequisites.profileProblems(profile(), 70f, today).isEmpty())
    }

    @Test
    fun invalidAge_mapsToReadableBirthDateCause() {
        for (birth in listOf(today.minusYears(13), today.minusYears(121), today, today.plusDays(1))) {
            val problems = PlanPrerequisites.profileProblems(profile(birth = birth.toEpochDay()), 70f, today)
            assertEquals(listOf(PlanPrerequisite.BIRTH_DATE_INVALID), problems)
        }
        assertTrue(PlanPrerequisites.profileProblems(profile(birth = today.minusYears(14).toEpochDay()), 70f, today).isEmpty())
        assertTrue(PlanPrerequisites.profileProblems(profile(birth = today.minusYears(120).toEpochDay()), 70f, today).isEmpty())
    }

    @Test
    fun missingFields_mapToOrderedCauses() {
        val problems = PlanPrerequisites.profileProblems(profile(birth = null, sex = " ", height = null), null, today)
        assertEquals(
            listOf(PlanPrerequisite.BIRTH_DATE_MISSING, PlanPrerequisite.SEX, PlanPrerequisite.HEIGHT, PlanPrerequisite.WEIGHT),
            problems,
        )
    }

    @Test
    fun message_isReadableItalianWithoutRawCodes() {
        val text = PlanPrerequisites.message(listOf(PlanPrerequisite.BIRTH_DATE_INVALID, PlanPrerequisite.WEIGHT, PlanPrerequisite.WEIGHT))
        assertEquals(
            "Completa prima: controlla la data di nascita: l'età deve essere compresa tra 14 e 120 anni; inserisci il peso nel profilo",
            text,
        )
        assertFalse(text.contains("NEEDS_INPUT"))
    }

    @Test
    fun needsInput_exposesMessageFieldsAndDestination() {
        val error = NutritionPlanGenerationService.GenerationException.NeedsInput(listOf(PlanPrerequisite.BIRTH_DATE_INVALID))
        assertEquals(PlanPrerequisites.message(error.prerequisites), error.message)
        assertEquals(listOf(PlanPrerequisite.BIRTH_DATE_INVALID.cause), error.fields)
        assertEquals(PlanPrerequisite.Destination.PROFILE_EDITOR, error.prerequisites.single().destination)
    }

    @Test
    fun needsProfileEditor_onlyForProfileCauses() {
        assertTrue(PlanPrerequisites.needsProfileEditor(PlanPrerequisites.message(listOf(PlanPrerequisite.BIRTH_DATE_INVALID))))
        assertTrue(PlanPrerequisites.needsProfileEditor(PlanPrerequisites.message(listOf(PlanPrerequisite.ACTIVE_PROFILE, PlanPrerequisite.WEIGHT))))
        assertFalse(PlanPrerequisites.needsProfileEditor(PlanPrerequisites.message(listOf(PlanPrerequisite.ACTIVE_PROFILE))))
        assertFalse(PlanPrerequisites.needsProfileEditor("Le settimane concluse sono storico in sola lettura."))
        assertFalse(PlanPrerequisites.needsProfileEditor(null))
    }

    @Test
    fun everyPrerequisiteHasNonBlankCauseAndNoCodes() {
        PlanPrerequisite.entries.forEach {
            assertTrue(it.cause.isNotBlank())
            assertFalse(it.cause.contains("_"))
        }
    }
}
