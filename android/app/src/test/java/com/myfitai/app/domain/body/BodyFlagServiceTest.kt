package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.body.FlagFixtures.TODAY
import com.myfitai.app.domain.body.FlagFixtures.ZONE
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FLAG L2: servizio che compone profilo + BIA + misure e chiama il motore. Dati sintetici, data di oggi iniettata
 * (nessun valore reale dei profili dell'utente). Le soglie del motore non si ritestano qui: si verifica la
 * composizione degli input, l'eta dal profilo e i suggerimenti sui dati mancanti.
 */
class BodyFlagServiceTest {
    private fun profile(
        birth: LocalDate? = TODAY.minusYears(40),
        height: Float? = 164f,
        sex: String? = "Femmina",
    ) = UserProfileEntity(
        id = 1,
        name = "Test",
        birthDateEpochDay = birth?.toEpochDay(),
        heightCm = height,
        currentWeightKg = null,
        goal = "Mantenimento",
        activityLevel = null,
        wakeTimeMinutes = null,
        sleepTimeMinutes = null,
        dietaryPreferencesJson = null,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        biologicalSex = sex,
    )

    private fun report(
        profile: UserProfileEntity = profile(),
        bia: List<com.myfitai.app.data.local.entity.BiaMeasurementEntity> = emptyList(),
        body: List<com.myfitai.app.data.local.entity.BodyMeasurementEntity> = emptyList(),
        today: LocalDate = TODAY,
    ) = BodyFlagService.evaluate(profile, bia, body, today, ZONE)

    @Test
    fun waistAndHeightFromProfile_produceWaistFlags_withTheHeightUsed() {
        val result = report(body = listOf(FlagFixtures.body(3, waist = 99f)))
        val codes = result.flags.map { it.code }
        assertTrue(BodyFlagCode.WHTR_HIGH in codes)
        assertTrue(BodyFlagCode.WAIST_SECOND_LEVEL in codes)
        assertEquals(164.0, result.heightCm!!, 0.0)
        assertEquals(BodyFlagThresholds.VERSION, result.thresholdsVersion)
    }

    @Test
    fun heightFloatNoise_isRemoved() {
        val result = report(profile = profile(height = 172.3f))
        assertEquals(172.3, result.heightCm!!, 0.0)
    }

    @Test
    fun ageComesFromBirthDate_adultBoundary() {
        val body = listOf(FlagFixtures.body(3, waist = 99f))
        // Compie 18 anni oggi: adulto, i flag esistono.
        assertTrue(report(profile = profile(birth = TODAY.minusYears(18)), body = body).flags.isNotEmpty())
        // Compie 18 anni domani: ancora minorenne, nessun flag e suggerimento dedicato.
        val minor = report(profile = profile(birth = TODAY.minusYears(18).plusDays(1)), body = body)
        assertTrue(minor.flags.isEmpty())
        assertEquals(BodyFlagHint.NOT_ADULT, minor.hint)
    }

    @Test
    fun missingOrFutureBirthDate_asksForBirthDate() {
        val body = listOf(FlagFixtures.body(3, waist = 99f))
        val missing = report(profile = profile(birth = null), body = body)
        assertTrue(missing.flags.isEmpty())
        assertEquals(BodyFlagHint.ADD_BIRTH_DATE, missing.hint)
        assertEquals(BodyFlagHint.ADD_BIRTH_DATE, report(profile = profile(birth = TODAY.plusDays(5)), body = body).hint)
    }

    @Test
    fun missingOrImplausibleHeight_asksForHeight_andReportsNoHeight() {
        val body = listOf(FlagFixtures.body(3, waist = 99f))
        val missing = report(profile = profile(height = null), body = body)
        assertEquals(BodyFlagHint.ADD_HEIGHT, missing.hint)
        assertNull(missing.heightCm)
        val implausible = report(profile = profile(height = 90f), body = body)
        assertEquals(BodyFlagHint.ADD_HEIGHT, implausible.hint)
        assertNull(implausible.heightCm)
    }

    @Test
    fun missingOrStaleWaist_suggestsMeasuringWaist() {
        assertEquals(BodyFlagHint.MEASURE_WAIST, report().hint)
        val stale = report(body = listOf(FlagFixtures.body(200, waist = 99f)))
        assertTrue(stale.flags.isEmpty())
        assertEquals(BodyFlagHint.MEASURE_WAIST, stale.hint)
    }

    @Test
    fun todayIsInjected_sameDataBecomesStaleLater() {
        val body = listOf(FlagFixtures.body(3, waist = 99f))
        assertTrue(report(body = body).flags.isNotEmpty())
        val later = report(body = body, today = TODAY.plusDays(200))
        assertTrue(later.flags.isEmpty())
        assertEquals(BodyFlagHint.MEASURE_WAIST, later.hint)
    }

    @Test
    fun healthyWaistWithSingleBia_suggestsMoreBia() {
        val result = report(
            profile = profile(height = 180f, sex = "Maschio"),
            bia = listOf(FlagFixtures.bia(5, fat = 20f)),
            body = listOf(FlagFixtures.body(3, waist = 70f)),
        )
        assertTrue(result.flags.isEmpty())
        assertEquals(BodyFlagHint.ADD_BIA, result.hint)
        assertFalse(result.biaTrendNeedsSameConditions)
    }

    @Test
    fun undeclaredBiaConditions_noTrendFlag_andExplainsWhy() {
        val bia = listOf(
            FlagFixtures.bia(60, fat = 25f, conditions = false),
            FlagFixtures.bia(40, fat = 24.5f, conditions = false),
            FlagFixtures.bia(20, fat = 24f, conditions = false),
            FlagFixtures.bia(0, fat = 23f, conditions = false),
        )
        val result = report(
            profile = profile(height = 180f, sex = "Maschio"),
            bia = bia,
            body = listOf(FlagFixtures.body(3, waist = 70f)),
        )
        assertTrue(result.flags.isEmpty())
        assertTrue(result.biaTrendNeedsSameConditions)
        // La nota sulle condizioni sostituisce il suggerimento di registrare altre BIA.
        assertNull(result.hint)
    }

    @Test
    fun declaredComparableBia_producesTrendFlag_withoutTheConditionsNote() {
        val bia = listOf(
            FlagFixtures.bia(60, fat = 25f),
            FlagFixtures.bia(40, fat = 24f),
            FlagFixtures.bia(20, fat = 23f),
            FlagFixtures.bia(0, fat = 22f),
        )
        val result = report(
            profile = profile(height = 180f, sex = "Maschio"),
            bia = bia,
            body = listOf(FlagFixtures.body(3, waist = 70f)),
        )
        assertEquals(listOf(BodyFlagCode.BODY_FAT_TREND_DOWN), result.flags.map { it.code })
        assertFalse(result.biaTrendNeedsSameConditions)
        assertNull(result.hint)
    }

    @Test
    fun sameInputs_sameReport() {
        val body = listOf(FlagFixtures.body(3, waist = 99f), FlagFixtures.body(30, waist = 97f))
        assertEquals(report(body = body), report(body = body.reversed()))
    }
}
