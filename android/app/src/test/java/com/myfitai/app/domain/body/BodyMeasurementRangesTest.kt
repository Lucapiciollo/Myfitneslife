package com.myfitai.app.domain.body

import com.myfitai.app.domain.body.BodyMeasurementRanges.Check
import com.myfitai.app.domain.body.BodyMeasurementRanges.Field
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMeasurementRangesTest {

    @Test
    fun weightRange_isThirtyToThreeHundredKg() {
        assertEquals(30f, Field.WEIGHT.min, 0f)
        assertEquals(300f, Field.WEIGHT.max, 0f)
        assertEquals("kg", Field.WEIGHT.unit)
    }

    @Test
    fun everyField_hasCoherentRangeAndUnit() {
        Field.entries.forEach { field ->
            assertTrue("${field.name} min < max", field.min in 0.1f..field.max)
            assertTrue("${field.name} max <= 300", field.max <= 300f)
            assertEquals(if (field == Field.WEIGHT) "kg" else "cm", field.unit)
        }
        assertEquals(13, Field.entries.size)
    }

    @Test
    fun valueInsideRange_isValid_andBoundariesAreInclusive() {
        assertEquals(Check.Valid(86.5f), BodyMeasurementRanges.check(Field.WEIGHT, "86,5"))
        assertEquals(Check.Valid(86.5f), BodyMeasurementRanges.check(Field.WEIGHT, " 86.5 "))
        assertEquals(Check.Valid(30f), BodyMeasurementRanges.check(Field.WEIGHT, "30"))
        assertEquals(Check.Valid(300f), BodyMeasurementRanges.check(Field.WEIGHT, "300"))
    }

    @Test
    fun outOfRange_reportsRangeForTheMessage() {
        assertEquals(Check.OutOfRange(30f, 300f), BodyMeasurementRanges.check(Field.WEIGHT, "29,9"))
        assertEquals(Check.OutOfRange(30f, 300f), BodyMeasurementRanges.check(Field.WEIGHT, "785"))
        val waist = BodyMeasurementRanges.check(Field.WAIST, "5") as Check.OutOfRange
        assertEquals(Field.WAIST.min, waist.min, 0f)
        assertEquals(Field.WAIST.max, waist.max, 0f)
        assertTrue(BodyMeasurementRanges.check(Field.ARM_LEFT, "250") is Check.OutOfRange)
    }

    @Test
    fun blankInput_isEmpty() {
        assertEquals(Check.Empty, BodyMeasurementRanges.check(Field.CHEST, null))
        assertEquals(Check.Empty, BodyMeasurementRanges.check(Field.CHEST, ""))
        assertEquals(Check.Empty, BodyMeasurementRanges.check(Field.CHEST, "   "))
    }

    @Test
    fun malformedInput_isNotANumber() {
        listOf("abc", "NaN", "Infinity", "-5", "1e2", "1,2,3", "1.000,5", ",", ".", "8 6").forEach { raw ->
            assertEquals("'$raw'", Check.NotANumber, BodyMeasurementRanges.check(Field.WEIGHT, raw))
        }
        assertNull(BodyMeasurementRanges.parse("NaN"))
        assertEquals(86f, BodyMeasurementRanges.parse("86.")!!, 0f)
        assertEquals(0.5f, BodyMeasurementRanges.parse(",5")!!, 0f)
    }

    @Test
    fun futureDate_isRejected_todayAndPastAreAccepted() {
        val today = LocalDate.of(2026, 11, 12)
        assertTrue(BodyMeasurementRanges.isFutureDate(today.plusDays(1), today))
        assertFalse(BodyMeasurementRanges.isFutureDate(today, today))
        assertFalse(BodyMeasurementRanges.isFutureDate(today.minusDays(1), today))
    }

    @Test
    fun formatBound_dropsTrailingZero() {
        assertEquals("30", BodyMeasurementRanges.formatBound(30f))
        assertEquals("12,5", BodyMeasurementRanges.formatBound(12.5f))
    }
}
