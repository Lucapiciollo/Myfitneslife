package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.body.BodyValueRanges.BiaField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BiaFormRulesTest {

    @Test
    fun check_acceptsCommaAndDot() {
        assertEquals(BiaFormRules.Check.Valid(78.5f), BiaFormRules.check(BiaField.WEIGHT, "78,5"))
        assertEquals(BiaFormRules.Check.Valid(78.5f), BiaFormRules.check(BiaField.WEIGHT, " 78.5 "))
    }

    @Test
    fun check_acceptsOnlyDigitsWithAtMostOneDecimalSeparator() {
        // The reported case: "86..5" was read as 86,5. Every malformed number is refused with NotANumber.
        listOf("86..5", "86,,5", "86.,5", "86,.5", "8.6.5", "8,6,5", "1e2", "1E2", "-5", "+5", "8 6", "86 .5", "86,5kg", "٨٦", "--", ",", ".").forEach { raw ->
            assertEquals("'$raw' must be refused", BiaFormRules.Check.NotANumber, BiaFormRules.check(BiaField.WEIGHT, raw))
        }
        assertEquals(BiaFormRules.Check.Empty, BiaFormRules.check(BiaField.WEIGHT, ""))
        assertEquals(BiaFormRules.Check.Empty, BiaFormRules.check(BiaField.WEIGHT, null))
        assertEquals(BiaFormRules.Check.Valid(86.5f), BiaFormRules.check(BiaField.WEIGHT, "86,5"))
        assertEquals(BiaFormRules.Check.Valid(86.5f), BiaFormRules.check(BiaField.WEIGHT, "86.5"))
        assertEquals(BiaFormRules.Check.Valid(86f), BiaFormRules.check(BiaField.WEIGHT, "86"))
        // Whole-number fields follow the same rule.
        assertEquals(BiaFormRules.Check.NotANumber, BiaFormRules.check(BiaField.BODY_AGE, "3..2"))
        assertEquals(BiaFormRules.Check.NotANumber, BiaFormRules.check(BiaField.BMR, "1800,,5"))
    }

    @Test
    fun check_emptyIsEmpty_butTextIsNeverSilentlyEmpty() {
        assertEquals(BiaFormRules.Check.Empty, BiaFormRules.check(BiaField.WEIGHT, "  "))
        assertEquals(BiaFormRules.Check.NotANumber, BiaFormRules.check(BiaField.WEIGHT, "abc"))
        assertEquals(BiaFormRules.Check.NotANumber, BiaFormRules.check(BiaField.WEIGHT, "7,8,5"))
        assertEquals(BiaFormRules.Check.NotANumber, BiaFormRules.check(BiaField.WEIGHT, "-80"))
    }

    @Test
    fun check_usesTheRangesOfBodyValueRanges() {
        assertEquals(BiaFormRules.Check.OutOfRange(BodyValueRanges.WEIGHT_KG), BiaFormRules.check(BiaField.WEIGHT, "785"))
        assertEquals(BiaFormRules.Check.OutOfRange(BodyValueRanges.WEIGHT_KG), BiaFormRules.check(BiaField.WEIGHT, "29,9"))
        assertEquals(BiaFormRules.Check.Valid(30f), BiaFormRules.check(BiaField.WEIGHT, "30"))
        assertEquals(BiaFormRules.Check.OutOfRange(BodyValueRanges.BODY_FAT_PERCENT), BiaFormRules.check(BiaField.BODY_FAT, "1"))
        assertEquals(BiaFormRules.Check.Valid(60f), BiaFormRules.check(BiaField.BODY_FAT, "60"))
        val described = BodyValueRanges.WEIGHT_KG.describe()
        assertTrue(described, described.startsWith("30") && described.endsWith("300 kg"))
    }

    @Test
    fun check_bodyAgeMustBeWhole() {
        assertEquals(BiaFormRules.Check.NotWhole, BiaFormRules.check(BiaField.BODY_AGE, "32,5"))
        assertEquals(BiaFormRules.Check.Valid(32f), BiaFormRules.check(BiaField.BODY_AGE, "32"))
    }

    @Test
    fun isFutureDay_comparesLocalDays() {
        val zone = ZoneId.of("Europe/Rome")
        val now = ZonedDateTime.of(2026, 11, 12, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val laterToday = ZonedDateTime.of(2026, 11, 12, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        val tomorrow = ZonedDateTime.of(2026, 11, 13, 0, 1, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 11, 11, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertFalse(BiaFormRules.isFutureDay(laterToday, now, zone))
        assertTrue(BiaFormRules.isFutureDay(tomorrow, now, zone))
        assertFalse(BiaFormRules.isFutureDay(yesterday, now, zone))
    }

    @Test
    fun isDirty_isFalseWithoutBaselineOrChange() {
        val base = BiaFormRules.snapshot(emptyMap(), 1_000L, listOf(false, false, false, false))
        assertFalse(BiaFormRules.isDirty(null, base))
        assertFalse(BiaFormRules.isDirty(base, base.copy()))
    }

    @Test
    fun isDirty_detectsValueDateAndConditionChanges() {
        val base = BiaFormRules.snapshot(mapOf(BiaField.WEIGHT to 80f), 1_000L, listOf(false, false, false, false))
        assertTrue(BiaFormRules.isDirty(base, BiaFormRules.snapshot(mapOf(BiaField.WEIGHT to 80.5f), 1_000L, listOf(false, false, false, false))))
        assertTrue(BiaFormRules.isDirty(base, BiaFormRules.snapshot(mapOf(BiaField.WEIGHT to 80f), 2_000L, listOf(false, false, false, false))))
        assertTrue(BiaFormRules.isDirty(base, BiaFormRules.snapshot(mapOf(BiaField.WEIGHT to 80f), 1_000L, listOf(true, false, false, false))))
        assertTrue(BiaFormRules.isDirty(base, BiaFormRules.snapshot(emptyMap(), 1_000L, listOf(false, false, false, false))))
    }

    @Test
    fun arrayRoundTrip_keepsNullsAndValues() {
        val values = BiaField.entries.map { if (it == BiaField.WEIGHT) 80.5f else null }
        val restored = BiaFormRules.fromArray(BiaFormRules.toArray(values))
        assertEquals(values, restored)
        assertTrue(BiaFormRules.fromArray(null).all { it == null })
        assertEquals(BiaField.entries.size, BiaFormRules.fromArray(null).size)
    }

    @Test
    fun valuesOf_mapsEveryFieldOfAStoredReading() {
        val item = BiaMeasurementEntity(
            profileId = 1L, measuredAtEpochMillis = 0L, weightKg = 80f, bodyFatPercent = 20f, visceralFatLevel = 8f,
            muscleMassKg = 60f, skeletalMuscleKg = 30f, bodyWaterPercent = 55f, bmrKcal = 1800f,
            fasting = false, justWokeUp = false, afterBathroom = false, noRecentWorkout = false,
            bodyAgeYears = 33, bmi = 24f,
        )
        val values = BiaFormRules.valuesOf(item)
        assertEquals(BiaField.entries.toSet(), values.keys)
        assertEquals(33f, values[BiaField.BODY_AGE])
        assertNull(values[BiaField.BONE])
    }

    @Test
    fun inputText_usesItalianDecimalSeparator() {
        assertEquals("76,8", BiaFormRules.inputText(76.8f))
        assertEquals("76", BiaFormRules.inputText(76f))
        assertEquals("", BiaFormRules.inputText(null))
    }
}
