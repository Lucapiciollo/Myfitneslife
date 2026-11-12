package com.myfitai.app.domain.body

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMeasurementFormStateTest {
    private val day = LocalDate.of(2026, 11, 12)
    private val empty = List(13) { "" }

    @Test
    fun untouchedForm_isNotDirty() {
        assertFalse(BodyMeasurementFormState.isDirty(empty, empty, day, day))
    }

    @Test
    fun typedValue_makesFormDirty() {
        val current = empty.toMutableList().also { it[0] = "80" }
        assertTrue(BodyMeasurementFormState.isDirty(empty, current, day, day))
    }

    @Test
    fun changedDate_makesFormDirty() {
        assertTrue(BodyMeasurementFormState.isDirty(empty, empty, day, day.minusDays(1)))
    }

    @Test
    fun equivalentNumbers_areNotDirty() {
        val baseline = empty.toMutableList().also { it[0] = "86.5" }
        val current = empty.toMutableList().also { it[0] = " 86,50 " }
        assertFalse(BodyMeasurementFormState.isDirty(baseline, current, day, day))
    }

    @Test
    fun whitespaceOnly_isNotDirty() {
        val current = empty.toMutableList().also { it[3] = "  " }
        assertFalse(BodyMeasurementFormState.isDirty(empty, current, day, day))
    }

    @Test
    fun clearedValue_isDirty() {
        val baseline = empty.toMutableList().also { it[2] = "90.0" }
        assertTrue(BodyMeasurementFormState.isDirty(baseline, empty, day, day))
    }

    @Test
    fun differentSizes_areDirty() {
        assertTrue(BodyMeasurementFormState.isDirty(empty, empty.drop(1), day, day))
    }

    @Test
    fun normalize_unifiesDecimalSeparatorAndWhitespace() {
        assertEquals("86.5", BodyMeasurementFormState.normalize(" 86,5 "))
        assertEquals("", BodyMeasurementFormState.normalize(null))
    }
}
