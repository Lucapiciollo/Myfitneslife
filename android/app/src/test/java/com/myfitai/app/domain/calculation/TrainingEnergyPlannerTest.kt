package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
import com.myfitai.app.domain.calculation.LocalCalculationEngine.ActivityLevel
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import com.myfitai.app.domain.calculation.TrainingDayResolver.CheckIn
import com.myfitai.app.domain.calculation.TrainingDayResolver.RegisteredWorkout
import com.myfitai.app.domain.calculation.TrainingEnergyPlanner.ActivityBasis
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingEnergyPlannerTest {
    // Real profile: BMR 1918 kcal, 89.5 kg, weight loss, stored level "moderatamente attivo".
    private val monday = LocalDate.of(2026, 10, 5)
    private val week = (0L until 7L).map { monday.plusDays(it) }
    private val threeDays = TrainingProgram(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))

    private fun inputs(
        goal: Goal = Goal.WEIGHT_LOSS,
        basis: ActivityBasis = ActivityBasis.LEGACY_INCLUDES_TRAINING,
        program: TrainingProgram = threeDays,
        registered: Map<LocalDate, List<RegisteredWorkout>> = emptyMap(),
        checkIns: Map<LocalDate, CheckIn> = emptyMap(),
        ratio: Double = 1.0,
    ) = TrainingEnergyPlanner.Inputs(1918.0, 89.5, goal, ActivityLevel.MODERATE, basis, program, registered, checkIns, ratio)

    private fun plan(inputs: TrainingEnergyPlanner.Inputs) = TrainingEnergyPlanner.plan(week, inputs)!!

    @Test
    fun withoutTrainingDaysProgramModeDoesNotApply() {
        assertNull(TrainingEnergyPlanner.plan(week, inputs(program = TrainingProgram())))
        assertNull(TrainingEnergyPlanner.plan(emptyList(), inputs()))
    }

    @Test
    fun legacyProfilesAreLoweredOneStepAndConfirmedOnesAreNot() {
        val legacy = plan(inputs(basis = ActivityBasis.LEGACY_INCLUDES_TRAINING))
        val confirmed = plan(inputs(basis = ActivityBasis.EVERYDAY_ONLY))

        // 1.55 -> 1.375 for legacy profiles (numbers of the real profile), unchanged once confirmed.
        assertEquals(2242.0, legacy.first { !it.resolution.isTraining }.baseTargets.kcal, 1.0)
        assertEquals(2546.0, legacy.first { it.resolution.isTraining }.baseTargets.kcal, 1.0)
        assertEquals(2527.0, confirmed.first { !it.resolution.isTraining }.baseTargets.kcal, 1.0)
        assertEquals(2831.0, confirmed.first { it.resolution.isTraining }.baseTargets.kcal, 1.0)
        assertEquals(2372.0, TrainingEnergyPlanner.averageBaseKcal(legacy)!!, 1.0)
        assertEquals(3, legacy.count { it.resolution.isTraining })
    }

    @Test
    fun trainingDaysAreExactlyTheProgramDays() {
        val days = plan(inputs())

        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            days.filter { it.resolution.isTraining }.map { it.date.dayOfWeek },
        )
        assertTrue(days.filter { it.resolution.isTraining }.all { it.baseTargets.kcal > days.first { d -> !d.resolution.isTraining }.baseTargets.kcal })
    }

    @Test
    fun manualAndRegisteredInformationOverrideTheProgram() {
        val tuesday = monday.plusDays(1)
        val days = plan(
            inputs(
                registered = mapOf(tuesday to listOf(RegisteredWorkout(isRestDay = false, durationMinutes = 90))),
                checkIns = mapOf(monday to CheckIn(isTraining = false)),
            ),
        )

        // Tuesday has a registered workout (not in the program), Monday was manually marked as rest.
        assertTrue(days.first { it.date == tuesday }.resolution.isTraining)
        assertFalse(days.first { it.date == monday }.resolution.isTraining)
        assertEquals(TrainingDayResolver.Source.REGISTERED_WORKOUT, days.first { it.date == tuesday }.resolution.source)
        assertEquals(TrainingDayResolver.Source.MANUAL_CHECK_IN, days.first { it.date == monday }.resolution.source)
        // Program Mon/Wed/Fri, minus Monday (manual rest), plus Tuesday (registered workout).
        assertEquals(
            listOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            days.filter { it.resolution.isTraining }.map { it.date.dayOfWeek },
        )
    }

    @Test
    fun adaptiveCorrectionScalesEveryDayButNeverBelowTheBmr() {
        val base = plan(inputs())
        val scaled = plan(inputs(ratio = 1.04))
        val extreme = plan(inputs(ratio = 0.5))
        val ignored = plan(inputs(ratio = 3.0))

        base.zip(scaled).forEach { (a, b) -> assertEquals(a.baseTargets.kcal * 1.04, b.baseTargets.kcal, 1e-6) }
        assertTrue(extreme.all { it.baseTargets.kcal >= 1918.0 })
        // A nonsensical ratio is ignored instead of distorting the plan.
        base.zip(ignored).forEach { (a, b) -> assertEquals(a.baseTargets.kcal, b.baseTargets.kcal, 1e-9) }
    }

    @Test
    fun calorieCeilingIsTheDaysExpenditureOnlyForDeficitGoals() {
        val deficit = plan(inputs(goal = Goal.WEIGHT_LOSS))
        val recomposition = plan(inputs(goal = Goal.RECOMPOSITION))
        val maintenance = plan(inputs(goal = Goal.MAINTENANCE))

        assertTrue(deficit.all { it.ceilingKcal == it.energy.tdeeKcal && it.baseTargets.kcal < it.ceilingKcal!! })
        assertTrue(recomposition.all { it.ceilingKcal == it.energy.tdeeKcal })
        assertTrue(maintenance.all { it.ceilingKcal == null })
        // A training day has a higher ceiling than a rest day, so its larger target is not rejected.
        assertTrue(deficit.first { it.resolution.isTraining }.ceilingKcal!! > deficit.first { !it.resolution.isTraining }.ceilingKcal!!)
    }

    @Test
    fun proteinAndFatDoNotChangeBetweenDayTypes() {
        val days = plan(inputs())
        val training = days.first { it.resolution.isTraining }.baseTargets
        val rest = days.first { !it.resolution.isTraining }.baseTargets

        assertEquals(rest.proteinG, training.proteinG, 1e-9)
        assertEquals(rest.fatG, training.fatG, 1e-9)
        assertTrue(training.carbsG > rest.carbsG)
        assertEquals(2.0 * 89.5, training.proteinG, 1e-9)
    }

    @Test
    fun unusableProfileDataProducesNoPlan() {
        assertNull(TrainingEnergyPlanner.plan(week, inputs().copy(bmrKcal = 0.0)))
        assertNull(TrainingEnergyPlanner.plan(week, inputs().copy(weightKg = Double.NaN)))
    }

    @Test
    fun trainingSessionsKeepTheirShapeFromTheProgram() {
        val program = TrainingProgram(setOf(DayOfWeek.MONDAY), durationMinutes = 90, intensity = Intensity.HARD, startMinutes = 18 * 60)

        val day = plan(inputs(program = program)).first { it.resolution.isTraining }

        assertEquals(TrainingSession(90, Intensity.HARD, 18 * 60), day.resolution.session)
        assertEquals(DayEnergyEngine.exerciseKcal(89.5, day.resolution.session), day.energy.exerciseKcal)
    }
}
