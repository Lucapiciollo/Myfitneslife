package com.myfitai.app.domain.ai

import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.calculation.WeightResolver
import com.myfitai.app.domain.calculation.WeightSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AiUserContextTest {
    @Test
    fun profileLine_containsCanonicalTaskContextWithoutBirthDate() {
        val profile = UserProfileEntity(
            name = "Test",
            birthDateEpochDay = LocalDate.of(1983, 8, 9).toEpochDay(),
            heightCm = 186f,
            currentWeightKg = 90f,
            goal = "Ricomposizione",
            activityLevel = "Attivo",
            wakeTimeMinutes = 420,
            sleepTimeMinutes = 1380,
            dietaryPreferencesJson = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            biologicalSex = "Maschio",
        )

        val line = AiUserContext.profileLine(profile, LocalDate.of(2026, 9, 23), effectiveWeightKg = 89f)

        assertEquals("Maschio|43|186.0|89.0|Ricomposizione|Attivo|420|1380", line)
    }

    private fun profile(weightKg: Float?) = UserProfileEntity(
        name = "Test",
        birthDateEpochDay = LocalDate.of(1983, 8, 9).toEpochDay(),
        heightCm = 186f,
        currentWeightKg = weightKg,
        goal = "Dimagrimento",
        activityLevel = "Attivo",
        wakeTimeMinutes = 420,
        sleepTimeMinutes = 1380,
        dietaryPreferencesJson = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        biologicalSex = "Maschio",
    )

    private fun bia(measuredAt: Long, weightKg: Float?) = BiaMeasurementEntity(
        profileId = 1,
        measuredAtEpochMillis = measuredAt,
        weightKg = weightKg,
        bodyFatPercent = null,
        visceralFatLevel = null,
        muscleMassKg = null,
        skeletalMuscleKg = null,
        bodyWaterPercent = null,
        bmrKcal = null,
        fasting = false,
        justWokeUp = false,
        afterBathroom = false,
        noRecentWorkout = false,
    )

    @Test
    fun promptWeight_usesResolvedWeightNotTheStaticProfileWeight() {
        val today = LocalDate.of(2026, 9, 23)
        val profile = profile(90f)
        // Stessa chiamata dei servizi (WeeklyReview, NutritionAdvice, NutritionPath): resolver + profileLine.
        val resolved = WeightResolver.resolve(listOf(bia(1_000L, 84.5f)), emptyList(), profile.currentWeightKg, 1L)
        assertEquals(WeightSource.BIA, resolved.source)
        assertEquals(
            "Maschio|43|186.0|84.5|Dimagrimento|Attivo|420|1380",
            AiUserContext.profileLine(profile, today, resolved.valueKg),
        )
    }

    @Test
    fun promptWeight_fallsBackToProfileWeightOnlyWithoutReadings() {
        val today = LocalDate.of(2026, 9, 23)
        val profile = profile(90f)
        val resolved = WeightResolver.resolve(emptyList(), emptyList(), profile.currentWeightKg, profile.id)
        assertEquals(WeightSource.PROFILO, resolved.source)
        assertEquals(
            "Maschio|43|186.0|90.0|Dimagrimento|Attivo|420|1380",
            AiUserContext.profileLine(profile, today, resolved.valueKg),
        )
    }
}
