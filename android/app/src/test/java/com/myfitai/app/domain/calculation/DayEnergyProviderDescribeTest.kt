package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.LocalCalculationEngine.ActivityLevel
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

class DayEnergyProviderDescribeTest {
    private fun days(checkIn: TrainingDayResolver.CheckIn? = null): List<TrainingEnergyPlanner.Day> = TrainingEnergyPlanner.plan(
        dates = listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16)),
        inputs = TrainingEnergyPlanner.Inputs(
            bmrKcal = 1918.0, weightKg = 89.5, goal = Goal.WEIGHT_LOSS, activityLevel = ActivityLevel.MODERATE,
            activityBasis = TrainingEnergyPlanner.ActivityBasis.LEGACY_INCLUDES_TRAINING,
            program = TrainingProgram(setOf(DayOfWeek.WEDNESDAY)),
            checkIns = checkIn?.let { mapOf(LocalDate.of(2026, 9, 16) to it) }.orEmpty(),
        ),
    )!!

    @Test
    fun restAndTrainingDaysAreDescribedWithTheirCost() {
        val days = days()
        assertTrue(DayEnergyProvider.describe(days[0]).startsWith("Giorno di riposo"))
        val training = DayEnergyProvider.describe(days[1])
        assertTrue(training, training.startsWith("Allenamento 60 min moderata · +"))
        assertTrue(training, training.endsWith("kcal"))
    }

    @Test
    fun aCheckInIsMentionedBecauseItOverridesTheProgram() {
        val rest = days(TrainingDayResolver.CheckIn(isTraining = false))[1]
        assertTrue(DayEnergyProvider.describe(rest), DayEnergyProvider.describe(rest).startsWith("Giorno di riposo (check-in di oggi)"))
    }
}