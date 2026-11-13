package com.myfitai.app.domain.calculation

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** UX7: l'impronta dipende solo dagli input nutrizionali, ed e stabile e riproducibile. */
class PlanFingerprintTest {

    private fun profile(
        name: String = "Luca",
        goal: String? = "WEIGHT_LOSS",
        activity: String? = "MODERATE",
        weight: Float? = 82.0f,
        photoPath: String? = null,
        wake: Int? = 420,
        sleep: Int? = 1380,
        prefs: String? = null,
        updatedAt: Long = 1L,
    ) = UserProfileEntity(
        id = 1,
        name = name,
        birthDateEpochDay = 9000L,
        heightCm = 180f,
        currentWeightKg = weight,
        goal = goal,
        activityLevel = activity,
        wakeTimeMinutes = wake,
        sleepTimeMinutes = sleep,
        dietaryPreferencesJson = prefs,
        photoPath = photoPath,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = updatedAt,
        biologicalSex = "MALE",
    )

    private fun of(p: UserProfileEntity, weight: Float? = p.currentWeightKg, bf: Float? = null, bfWeight: Float? = null) =
        PlanFingerprint.of(p, weight, bf, bfWeight)

    @Test
    fun stableAndShapedLikeHex() {
        val first = of(profile())
        assertEquals(first, of(profile()))
        assertEquals(PlanFingerprint.LENGTH, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun changingActivityOrGoalChangesIt() {
        val base = of(profile())
        assertNotEquals(base, of(profile(activity = "LIGHT")))
        assertNotEquals(base, of(profile(goal = "MUSCLE_GAIN")))
    }

    @Test
    fun changeAndRestoreGivesTheSameFingerprint() {
        val original = of(profile(activity = "MODERATE"))
        val changed = of(profile(activity = "LIGHT"))
        val restored = of(profile(activity = "MODERATE", updatedAt = 99_999L))
        assertNotEquals(original, changed)
        assertEquals(original, restored)
    }

    @Test
    fun nameFirstPhotoTimesAndPreferencesDoNotChangeIt() {
        val base = of(profile())
        assertEquals(base, of(profile(name = "Altro nome")))
        assertEquals(base, of(profile(photoPath = "/data/foto.jpg")))
        assertEquals(base, of(profile(wake = 360, sleep = 1320)))
        assertEquals(base, of(profile(prefs = "{\"vegetariano\":true}")))
    }

    @Test
    fun heightWeightSexAndBirthDateChangeIt() {
        val base = of(profile())
        assertNotEquals(base, of(profile(weight = 83.0f)))
        assertNotEquals(base, of(profile().copy(heightCm = 181f)))
        assertNotEquals(base, of(profile().copy(biologicalSex = "FEMALE")))
        assertNotEquals(base, of(profile().copy(birthDateEpochDay = 9001L)))
    }

    @Test
    fun floatsAreRoundedToOneDecimalSoNoiseDoesNotChangeIt() {
        assertEquals(of(profile(weight = 82.0f)), of(profile(weight = 82.0f), weight = 82.04f))
        assertNotEquals(of(profile(weight = 82.0f)), of(profile(weight = 82.0f), weight = 82.1f))
    }

    @Test
    fun resolvedWeightAndBodyFatUsedByTheCalculationChangeIt() {
        val p = profile()
        val base = of(p)
        assertNotEquals(base, of(p, weight = 80.5f))
        assertNotEquals(base, of(p, bf = 18.0f, bfWeight = 82.0f))
        assertNotEquals(of(p, bf = 18.0f, bfWeight = 82.0f), of(p, bf = 19.0f, bfWeight = 82.0f))
    }

    @Test
    fun fromHistoryUsesTheSameWeightAndBodyFatSelectionAsTheEngine() {
        val today = LocalDate.of(2026, 11, 13)
        val zone = ZoneId.of("Europe/Rome")
        val noon = today.minusDays(3).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val p = profile()
        val bia = BiaMeasurementEntity(
            id = 1, profileId = 1, measuredAtEpochMillis = noon, weightKg = 80.0f, bodyFatPercent = 20.0f,
            visceralFatLevel = null, muscleMassKg = null, skeletalMuscleKg = null, bodyWaterPercent = null, bmrKcal = null,
            fasting = true, justWokeUp = true, afterBathroom = true, noRecentWorkout = true,
        )
        val body = BodyMeasurementEntity(
            id = 1, profileId = 1, measuredAtEpochMillis = noon + 1000, chestCm = null, waistCm = 90f, abdomenCm = null,
            shouldersCm = null, glutesCm = null, armLeftCm = null, armRightCm = null, thighLeftCm = null,
            thighRightCm = null, calfLeftCm = null, calfRightCm = null,
        )
        val withBia = PlanFingerprint.of(p, listOf(bia), listOf(body), today, zone)
        assertEquals(PlanFingerprint.of(p, 80.0f, 20.0f, 80.0f), withBia)
        // Una misura di sola vita non cambia peso ne grasso usati dal calcolo.
        assertEquals(withBia, PlanFingerprint.of(p, listOf(bia), emptyList(), today, zone))
        // Nessuna rilevazione: vale il peso del profilo.
        assertEquals(PlanFingerprint.of(p, 82.0f, null, null), PlanFingerprint.of(p, emptyList(), emptyList(), today, zone))
    }
}
