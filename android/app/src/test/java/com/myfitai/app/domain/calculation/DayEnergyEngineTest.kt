package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
import com.myfitai.app.domain.calculation.LocalCalculationEngine.ActivityLevel
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayEnergyEngineTest {
    // Real profile used to validate the model: BMR 1918 kcal, 89.5 kg, weight loss (-15%).
    private val bmr = 1918.0
    private val weight = 89.5
    private val oneHourModerate = TrainingSession(60, Intensity.MODERATE)
    private val monday = LocalDate.of(2026, 10, 5)

    private fun day(multiplier: Double, session: TrainingSession?, goal: Goal = Goal.WEIGHT_LOSS) =
        assertNotNull(DayEnergyEngine.calculate(DayEnergyEngine.Input(bmr, weight, multiplier, goal, session)))

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    @Test
    fun workoutCostUsesNetMetWeightAndDuration() {
        assertEquals(358, DayEnergyEngine.exerciseKcal(89.5, oneHourModerate))
        assertEquals(224, DayEnergyEngine.exerciseKcal(89.5, TrainingSession(60, Intensity.LIGHT)))
        assertEquals(537, DayEnergyEngine.exerciseKcal(89.5, TrainingSession(60, Intensity.HARD)))
        assertEquals(179, DayEnergyEngine.exerciseKcal(89.5, TrainingSession(30, Intensity.MODERATE)))
        assertEquals(0, DayEnergyEngine.exerciseKcal(89.5, null))
        assertEquals(0, DayEnergyEngine.exerciseKcal(Double.NaN, oneHourModerate))
    }

    @Test
    fun restDayHasFewerCaloriesAtTheSameDeficitPercentage() {
        val rest = day(1.375, null)
        val training = day(1.375, oneHourModerate)

        assertEquals(DayEnergyEngine.DayKind.REST, rest.kind)
        assertEquals(DayEnergyEngine.DayKind.TRAINING, training.kind)
        assertTrue(rest.targetKcal < training.targetKcal)
        // The deficit is always 15% of THAT day's expenditure.
        assertEquals(0.85, rest.targetKcal / rest.tdeeKcal, 1e-9)
        assertEquals(0.85, training.targetKcal / training.tdeeKcal, 1e-9)
        assertFalse(rest.floorApplied)
        assertFalse(training.floorApplied)
    }

    @Test
    fun realProfileNumbersForThreeTrainingDaysAtEachEverydayFactor() {
        val expected = mapOf(
            1.20 to Triple(1956.0, 2261.0, 2087.0),
            1.375 to Triple(2242.0, 2546.0, 2372.0),
            1.55 to Triple(2527.0, 2831.0, 2657.0),
        )
        val program = TrainingDayResolver.WeeklyProgram(
            mapOf(
                DayOfWeek.MONDAY to oneHourModerate,
                DayOfWeek.WEDNESDAY to oneHourModerate,
                DayOfWeek.FRIDAY to oneHourModerate,
            ),
        )
        val dates = (0L until 7L).map { monday.plusDays(it) }

        expected.forEach { (multiplier, numbers) ->
            val rest = day(multiplier, null).targetKcal
            val training = day(multiplier, oneHourModerate).targetKcal
            assertEquals("rest @$multiplier", numbers.first, rest, 1.0)
            assertEquals("training @$multiplier", numbers.second, training, 1.0)

            val plans = assertNotNull(
                DayEnergyEngine.forDates(dates, bmr, weight, multiplier, Goal.WEIGHT_LOSS) { date ->
                    TrainingDayResolver.resolve(date, program = program)
                },
            )
            assertEquals("average @$multiplier", numbers.third, assertNotNull(DayEnergyEngine.averageTargetKcal(plans)), 1.0)
            // With no floor in play the weekly average keeps exactly the goal percentage of the weekly expenditure.
            assertEquals(
                0.85,
                assertNotNull(DayEnergyEngine.averageTargetKcal(plans)) / assertNotNull(DayEnergyEngine.averageTdeeKcal(plans)),
                1e-9,
            )
            assertEquals(3, plans.count { it.resolution.isTraining })
        }
    }

    @Test
    fun proteinAndFatStayPerKilogramWhileCarbohydratesAbsorbTheDifference() {
        val rest = day(1.375, null)
        val training = day(1.375, oneHourModerate)

        assertEquals(2.0 * weight, rest.proteinG, 1e-9)
        assertEquals(rest.proteinG, training.proteinG, 1e-9)
        assertEquals(0.8 * weight, rest.fatG, 1e-9)
        assertEquals(rest.fatG, training.fatG, 1e-9)
        assertTrue(training.carbsG > rest.carbsG)
        assertEquals((training.targetKcal - rest.targetKcal) / 4.0, training.carbsG - rest.carbsG, 1e-6)
    }

    @Test
    fun targetNeverFallsBelowTheBmr() {
        val energy = assertNotNull(
            DayEnergyEngine.calculate(DayEnergyEngine.Input(1500.0, 80.0, 1.0, Goal.WEIGHT_LOSS, null)),
        )

        assertEquals(1500.0, energy.targetKcal, 1e-9)
        assertTrue(energy.floorApplied)
        // Macros are derived from the floored target, not from the lower computed one.
        assertEquals(
            energy.targetKcal,
            energy.proteinG * 4 + energy.fatG * 9 + energy.carbsG * 4,
            1e-6,
        )
    }

    @Test
    fun surplusGoalsStayAboveExpenditure() {
        val energy = day(1.375, oneHourModerate, Goal.MUSCLE_GAIN)

        assertEquals(1.10, energy.targetKcal / energy.tdeeKcal, 1e-9)
    }

    @Test
    fun unusableInputsProduceNoNumber() {
        val ok = DayEnergyEngine.Input(bmr, weight, 1.375, Goal.WEIGHT_LOSS, null)

        assertNull(DayEnergyEngine.calculate(ok.copy(bmrKcal = 0.0)))
        assertNull(DayEnergyEngine.calculate(ok.copy(bmrKcal = Double.NaN)))
        assertNull(DayEnergyEngine.calculate(ok.copy(weightKg = -1.0)))
        assertNull(DayEnergyEngine.calculate(ok.copy(lifestyleMultiplier = 0.5)))
        assertNull(DayEnergyEngine.calculate(ok.copy(lifestyleMultiplier = 3.0)))
        assertNull(DayEnergyEngine.forDates(listOf(monday), 0.0, weight, 1.375, Goal.WEIGHT_LOSS) {
            TrainingDayResolver.Resolution(null, TrainingDayResolver.Source.DEFAULT_REST)
        })
    }

    @Test
    fun legacyLevelsMoveOneStepDownBecauseTheyAlreadyCountedWorkouts() {
        assertEquals(ActivityLevel.SEDENTARY, DayEnergyEngine.everydayLevelFromLegacy(ActivityLevel.SEDENTARY))
        assertEquals(ActivityLevel.SEDENTARY, DayEnergyEngine.everydayLevelFromLegacy(ActivityLevel.LIGHT))
        assertEquals(ActivityLevel.LIGHT, DayEnergyEngine.everydayLevelFromLegacy(ActivityLevel.MODERATE))
        assertEquals(ActivityLevel.MODERATE, DayEnergyEngine.everydayLevelFromLegacy(ActivityLevel.VERY_ACTIVE))
        assertEquals(ActivityLevel.VERY_ACTIVE, DayEnergyEngine.everydayLevelFromLegacy(ActivityLevel.EXTREME))
        // The real profile moves from 1.55 to 1.375 and gets about 2372 kcal on average with 3 workouts.
        assertEquals(1.375, DayEnergyEngine.everydayLevelFromLegacy(ActivityLevel.MODERATE).multiplier, 1e-9)
    }

    @Test
    fun sessionsRejectImplausibleDurationsAndStartTimes() {
        assertTrue(runCatching { TrainingSession(5, Intensity.LIGHT) }.isFailure)
        assertTrue(runCatching { TrainingSession(241, Intensity.LIGHT) }.isFailure)
        assertTrue(runCatching { TrainingSession(60, Intensity.LIGHT, startMinutes = 1440) }.isFailure)
        assertTrue(runCatching { TrainingSession(60, Intensity.LIGHT, startMinutes = 18 * 60) }.isSuccess)
    }
}
