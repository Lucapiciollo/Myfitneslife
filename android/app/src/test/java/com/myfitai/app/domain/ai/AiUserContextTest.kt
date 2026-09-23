package com.myfitai.app.domain.ai

import com.myfitai.app.data.local.entity.UserProfileEntity
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
}
