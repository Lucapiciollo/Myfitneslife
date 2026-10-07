package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
import com.myfitai.app.domain.calculation.TrainingDayResolver.CheckIn
import com.myfitai.app.domain.calculation.TrainingDayResolver.RegisteredWorkout
import com.myfitai.app.domain.calculation.TrainingDayResolver.Source
import com.myfitai.app.domain.calculation.TrainingDayResolver.WeeklyProgram
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingDayResolverTest {
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)
    private val evening = TrainingSession(75, Intensity.HARD, startMinutes = 18 * 60 + 30)
    private val program = WeeklyProgram(mapOf(DayOfWeek.MONDAY to evening))

    @Test
    fun withoutAnyInformationTheDayIsRest() {
        val resolution = TrainingDayResolver.resolve(monday)

        assertFalse(resolution.isTraining)
        assertNull(resolution.session)
        assertEquals(Source.DEFAULT_REST, resolution.source)
    }

    @Test
    fun weeklyProgramMarksOnlyItsDays() {
        val trainingDay = TrainingDayResolver.resolve(monday, program = program)
        val restDay = TrainingDayResolver.resolve(tuesday, program = program)

        assertEquals(evening, trainingDay.session)
        assertEquals(Source.WEEKLY_PROGRAM, trainingDay.source)
        assertFalse(restDay.isTraining)
        assertEquals(Source.DEFAULT_REST, restDay.source)
        assertEquals(1, program.trainingDaysPerWeek)
    }

    @Test
    fun registeredWorkoutOnAProgramRestDayMakesItATrainingDay() {
        val resolution = TrainingDayResolver.resolve(
            tuesday,
            registered = listOf(RegisteredWorkout(isRestDay = false, durationMinutes = 45, startMinutes = 7 * 60)),
            program = program,
        )

        assertEquals(Source.REGISTERED_WORKOUT, resolution.source)
        assertEquals(45, resolution.session?.durationMinutes)
        assertEquals(TrainingDayResolver.DEFAULT_INTENSITY, resolution.session?.intensity)
        assertEquals(7 * 60, resolution.session?.startMinutes)
    }

    @Test
    fun registeredWorkoutWithoutDurationUsesTheProgramThenTheDefault() {
        val onProgramDay = TrainingDayResolver.resolve(monday, registered = listOf(RegisteredWorkout(false)), program = program)
        val onOtherDay = TrainingDayResolver.resolve(tuesday, registered = listOf(RegisteredWorkout(false)))

        assertEquals(75, onProgramDay.session?.durationMinutes)
        assertEquals(Intensity.HARD, onProgramDay.session?.intensity)
        assertEquals(TrainingDayResolver.DEFAULT_DURATION_MINUTES, onOtherDay.session?.durationMinutes)
    }

    @Test
    fun registeredRestDayOverridesTheProgram() {
        val resolution = TrainingDayResolver.resolve(monday, registered = listOf(RegisteredWorkout(isRestDay = true)), program = program)

        assertFalse(resolution.isTraining)
        assertEquals(Source.REGISTERED_WORKOUT, resolution.source)
    }

    @Test
    fun anExplicitWorkoutBeatsARestNoteOnTheSameDate() {
        val resolution = TrainingDayResolver.resolve(
            tuesday,
            registered = listOf(RegisteredWorkout(isRestDay = true), RegisteredWorkout(isRestDay = false, durationMinutes = 50)),
        )

        assertEquals(50, resolution.session?.durationMinutes)
    }

    @Test
    fun severalWorkoutsInADayAddUpAndAreCapped() {
        val resolution = TrainingDayResolver.resolve(
            tuesday,
            registered = listOf(
                RegisteredWorkout(false, durationMinutes = 120, startMinutes = 18 * 60),
                RegisteredWorkout(false, durationMinutes = 150, startMinutes = 7 * 60),
            ),
        )

        assertEquals(TrainingSession.MAX_DURATION_MINUTES, resolution.session?.durationMinutes)
        assertEquals(7 * 60, resolution.session?.startMinutes)
    }

    @Test
    fun tinyOrMissingDurationsAreClampedInsteadOfCrashing() {
        val resolution = TrainingDayResolver.resolve(tuesday, registered = listOf(RegisteredWorkout(false, durationMinutes = 1, startMinutes = 99_999)))

        assertEquals(TrainingSession.MIN_DURATION_MINUTES, resolution.session?.durationMinutes)
        assertNull(resolution.session?.startMinutes)
    }

    @Test
    fun manualRestCheckInBeatsEverythingElse() {
        val resolution = TrainingDayResolver.resolve(
            monday,
            checkIn = CheckIn(isTraining = false),
            registered = listOf(RegisteredWorkout(isRestDay = false, durationMinutes = 60)),
            program = program,
        )

        assertFalse(resolution.isTraining)
        assertEquals(Source.MANUAL_CHECK_IN, resolution.source)
    }

    @Test
    fun manualTrainingCheckInFillsMissingDetailsFromTheProgramThenDefaults() {
        val partial = TrainingDayResolver.resolve(monday, checkIn = CheckIn(true, durationMinutes = 30), program = program)
        val bare = TrainingDayResolver.resolve(tuesday, checkIn = CheckIn(true))

        assertEquals(Source.MANUAL_CHECK_IN, partial.source)
        assertEquals(30, partial.session?.durationMinutes)
        assertEquals(Intensity.HARD, partial.session?.intensity)
        assertEquals(18 * 60 + 30, partial.session?.startMinutes)
        assertNotNull(bare.session)
        assertEquals(TrainingDayResolver.DEFAULT_DURATION_MINUTES, bare.session?.durationMinutes)
        assertEquals(TrainingDayResolver.DEFAULT_INTENSITY, bare.session?.intensity)
        assertTrue(bare.isTraining)
    }
}
