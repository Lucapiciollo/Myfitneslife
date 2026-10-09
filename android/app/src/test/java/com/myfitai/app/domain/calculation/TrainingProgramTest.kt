package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
import java.time.DayOfWeek
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingProgramTest {
    @Test
    fun onlyCalorieRelevantFieldsRequireRegeneratingThePlan() {
        val original = TrainingProgram(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            durationMinutes = 60,
            intensity = DailyActivityCheckInEngine.Intensity.MODERATE,
            startMinutes = 8 * 60,
        )

        assertFalse(original.changesDailyCaloriesComparedTo(original.copy(startMinutes = 9 * 60)))
        assertTrue(original.changesDailyCaloriesComparedTo(original.copy(days = setOf(DayOfWeek.MONDAY))))
        assertTrue(original.changesDailyCaloriesComparedTo(original.copy(durationMinutes = 90)))
        assertTrue(original.changesDailyCaloriesComparedTo(original.copy(intensity = DailyActivityCheckInEngine.Intensity.HARD)))
        assertFalse(TrainingProgram().changesDailyCaloriesComparedTo(TrainingProgram(durationMinutes = 90)))
    }

    private val program = TrainingProgram(
        days = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        durationMinutes = 75,
        intensity = Intensity.HARD,
        startMinutes = 18 * 60 + 30,
    )

    @Test
    fun emptyProgramMeansRestEveryDay() {
        val empty = TrainingProgram()

        assertTrue(empty.isEmpty)
        assertEquals(0, empty.daysMask)
        assertEquals("Nessun giorno di allenamento impostato", empty.summary())
        assertEquals(0, empty.toWeeklyProgram().trainingDaysPerWeek)
        assertFalse(TrainingDayResolver.resolve(LocalDate.of(2026, 10, 5), program = empty.toWeeklyProgram()).isTraining)
    }

    @Test
    fun summaryListsDaysInWeekOrderWithShape() {
        assertEquals("Lun, Mer, Ven · 75 min · intensa · 18:30", program.summary())
        assertEquals("Mar · 60 min · moderata", TrainingProgram(days = setOf(DayOfWeek.TUESDAY)).summary())
    }

    @Test
    fun maskRoundTripsEveryCombination() {
        for (mask in 0..127) {
            val days = TrainingProgram.daysFromMask(mask)
            assertEquals(mask, TrainingProgram(days = days).daysMask)
        }
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), TrainingProgram.daysFromMask(0b1000001))
    }

    @Test
    fun storedValuesAreClampedInsteadOfFailing() {
        val corrupted = TrainingProgram.fromStored(mask = -1, durationMinutes = 9_999, intensityName = "???", startMinutes = 99_999)

        assertEquals(DayOfWeek.entries.toSet(), corrupted.days)
        assertEquals(TrainingSession.MAX_DURATION_MINUTES, corrupted.durationMinutes)
        assertEquals(TrainingProgram.DEFAULT_INTENSITY, corrupted.intensity)
        assertNull(corrupted.startMinutes)

        val defaults = TrainingProgram.fromStored(mask = 0, durationMinutes = 1, intensityName = null, startMinutes = -1)
        assertEquals(TrainingSession.MIN_DURATION_MINUTES, defaults.durationMinutes)
        assertNull(defaults.startMinutes)
    }

    @Test
    fun jsonRoundTripKeepsTheProgram() {
        val restored = TrainingProgram.fromJson(JSONObject(program.toJson().toString()))

        assertEquals(program, restored)
        assertNull(TrainingProgram.fromJson(JSONObject(TrainingProgram().toJson().toString()))?.startMinutes)
    }

    @Test
    fun absentJsonLeavesTheProfileUntouched() {
        assertNull(TrainingProgram.fromJson(null))
    }

    @Test
    fun malformedJsonIsSanitized() {
        val restored = TrainingProgram.fromJson(
            JSONObject("""{"days":["MONDAY","FUNDAY",5,"SUNDAY"],"durationMinutes":5000,"intensity":"EXTREME","startMinutes":-30}"""),
        )
        assertNotNull(restored)
        restored!!

        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), restored.days)
        assertEquals(TrainingSession.MAX_DURATION_MINUTES, restored.durationMinutes)
        assertEquals(TrainingProgram.DEFAULT_INTENSITY, restored.intensity)
        assertNull(restored.startMinutes)
    }

    @Test
    fun weeklyProgramFeedsTheResolverForTheSelectedDaysOnly() {
        val weekly = program.toWeeklyProgram()
        val monday = LocalDate.of(2026, 10, 5)

        assertEquals(3, weekly.trainingDaysPerWeek)
        val trainingDay = TrainingDayResolver.resolve(monday, program = weekly)
        assertEquals(TrainingSession(75, Intensity.HARD, 18 * 60 + 30), trainingDay.session)
        assertFalse(TrainingDayResolver.resolve(monday.plusDays(1), program = weekly).isTraining)
        assertTrue(TrainingDayResolver.resolve(monday.plusDays(4), program = weekly).isTraining)
    }

    @Test
    fun durationOptionsAreAllValidForASession() {
        TrainingProgram.DURATION_OPTIONS.forEach { minutes ->
            val program = TrainingProgram(days = setOf(DayOfWeek.MONDAY), durationMinutes = minutes)
            assertEquals(minutes, program.toWeeklyProgram().sessions.getValue(DayOfWeek.MONDAY).durationMinutes)
        }
    }
}
