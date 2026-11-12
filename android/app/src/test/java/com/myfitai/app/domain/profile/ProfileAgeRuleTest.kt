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
    fun warningStatus_ignoresMissingDateButFlagsOutOfRange() {
        assertEquals(ProfileAgeRule.Status.VALID, ProfileAgeRule.warningStatus(null, today))
        assertEquals(ProfileAgeRule.Status.VALID, ProfileAgeRule.warningStatus(birthYearsAgo(30), today))
        assertEquals(ProfileAgeRule.Status.TOO_YOUNG, ProfileAgeRule.warningStatus(birthYearsAgo(13), today))
        assertEquals(ProfileAgeRule.Status.TOO_OLD, ProfileAgeRule.warningStatus(birthYearsAgo(121), today))
        assertEquals(ProfileAgeRule.Status.FUTURE, ProfileAgeRule.warningStatus(today.plusDays(1).toEpochDay(), today))
    }

    @Test
    fun message_passesRuleLimitsToPlaceholders() {
        assertNull(ProfileAgeRule.message(ProfileAgeRule.Status.VALID) { _, _, _ -> "x" })
        val seen = ProfileAgeRule.message(ProfileAgeRule.Status.TOO_OLD) { res, min, max -> "$res:$min:$max" }
        assertEquals("${R.string.profile_birth_date_invalid}:14:120", seen)
    }

    @Test
    fun stringsXml_usesPlaceholdersNotHandWrittenRange() {
        val xml = java.io.File("src/main/res/values/strings.xml").readText()
        fun value(name: String) = Regex("<string name=\"$name\">(.*?)</string>").find(xml)!!.groupValues[1]
        for (name in listOf("profile_birth_date_invalid", "food_plan_profile_age_warning")) {
            val text = value(name)
            assertTrue(name, text.contains("%1\$d") && text.contains("%2\$d"))
            assertFalse(name, Regex("\\b(14|120)\\b").containsMatchIn(text))
        }
        assertTrue(value("profile_birth_date_future").contains("nel futuro"))
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
