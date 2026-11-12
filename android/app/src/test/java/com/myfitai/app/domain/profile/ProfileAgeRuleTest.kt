package com.myfitai.app.domain.profile

import com.myfitai.app.R
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProfileAgeRuleTest {
    private val today = LocalDate.of(2026, 11, 12)

    private fun birthYearsAgo(years: Int, extraDays: Long = 0): Long =
        today.minusYears(years.toLong()).minusDays(extraDays).toEpochDay()

    @Test
    fun rangeMatchesLocalCalculationEngine() {
        assertEquals(LocalCalculationEngine.MIN_AGE_YEARS, ProfileAgeRule.MIN_AGE_YEARS)
        assertEquals(LocalCalculationEngine.MAX_AGE_YEARS, ProfileAgeRule.MAX_AGE_YEARS)
        assertEquals(14, ProfileAgeRule.MIN_AGE_YEARS)
        assertEquals(120, ProfileAgeRule.MAX_AGE_YEARS)
    }

    @Test
    fun boundaries_13_14_120_121() {
        assertEquals(ProfileAgeRule.Status.TOO_YOUNG, ProfileAgeRule.check(birthYearsAgo(13), today))
        assertEquals(ProfileAgeRule.Status.VALID, ProfileAgeRule.check(birthYearsAgo(14), today))
        assertEquals(ProfileAgeRule.Status.VALID, ProfileAgeRule.check(birthYearsAgo(120), today))
        assertEquals(ProfileAgeRule.Status.TOO_OLD, ProfileAgeRule.check(birthYearsAgo(121), today))
    }

    @Test
    fun birthdayNotYetReached_countsCompletedYears() {
        // Un giorno prima del 14° compleanno: ancora 13 anni.
        val almost14 = today.minusYears(14).plusDays(1).toEpochDay()
        assertEquals(13, ProfileAgeRule.ageYears(almost14, today))
        assertEquals(ProfileAgeRule.Status.TOO_YOUNG, ProfileAgeRule.check(almost14, today))
        // Un giorno prima del 121° compleanno: ancora 120.
        val almost121 = today.minusYears(121).plusDays(1).toEpochDay()
        assertEquals(ProfileAgeRule.Status.VALID, ProfileAgeRule.check(almost121, today))
    }

    @Test
    fun ageZero_isInvalid() {
        assertEquals(0, ProfileAgeRule.ageYears(today.toEpochDay(), today))
        assertEquals(ProfileAgeRule.Status.TOO_YOUNG, ProfileAgeRule.check(today.toEpochDay(), today))
        assertFalse(ProfileAgeRule.isValid(today.toEpochDay(), today))
        assertTrue(ProfileAgeRule.isPresentButInvalid(today.toEpochDay(), today))
    }

    @Test
    fun futureDate_isInvalidWithoutAge() {
        val tomorrow = today.plusDays(1).toEpochDay()
        assertNull(ProfileAgeRule.ageYears(tomorrow, today))
        assertEquals(ProfileAgeRule.Status.FUTURE, ProfileAgeRule.check(tomorrow, today))
        assertTrue(ProfileAgeRule.isPresentButInvalid(tomorrow, today))
    }

    @Test
    fun missingDate_isMissingNotInvalid() {
        assertEquals(ProfileAgeRule.Status.MISSING, ProfileAgeRule.check(null, today))
        assertFalse(ProfileAgeRule.isValid(null, today))
        assertFalse(ProfileAgeRule.isPresentButInvalid(null, today))
    }

    @Test
    fun statusMessages_areMappedAndValidHasNone() {
        assertNull(ProfileAgeRule.Status.VALID.messageRes)
        assertEquals(R.string.validation_required_birth_date, ProfileAgeRule.Status.MISSING.messageRes)
        assertEquals(R.string.profile_birth_date_future, ProfileAgeRule.Status.FUTURE.messageRes)
        assertEquals(R.string.profile_birth_date_invalid, ProfileAgeRule.Status.TOO_YOUNG.messageRes)
        assertEquals(R.string.profile_birth_date_invalid, ProfileAgeRule.Status.TOO_OLD.messageRes)
    }

    @Test
    fun engineIgnoresAgeOutsideRuleRange() {
        fun bmrFor(age: Int) = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 70.0, heightCm = 170.0, ageYears = age,
                biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = LocalCalculationEngine.Goal.MAINTENANCE,
            )
        ).bmrKcal
        assertNull(bmrFor(13))
        assertTrue(bmrFor(14) != null)
        assertTrue(bmrFor(120) != null)
        assertNull(bmrFor(121))
    }
}
