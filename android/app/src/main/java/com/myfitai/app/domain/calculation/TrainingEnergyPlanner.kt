package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.LocalCalculationEngine.ActivityLevel
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import java.time.LocalDate
import kotlin.math.max

/**
 * Turns the weekly training program into per-day nutrition targets ("program mode").
 *
 * Program mode only applies when the profile has at least one training day. Without a program the
 * app keeps the single habitual target, which already contains an average amount of training.
 */
object TrainingEnergyPlanner {
    /** What the stored activity level means for a profile. */
    enum class ActivityBasis {
        /** Chosen before workouts could be scheduled: it already counts training, so it is lowered one step. */
        LEGACY_INCLUDES_TRAINING,

        /** Confirmed after the schedule existed: everyday activity only, workouts are added per day. */
        EVERYDAY_ONLY,
    }

    data class Inputs(
        val bmrKcal: Double,
        val weightKg: Double,
        val goal: Goal,
        val activityLevel: ActivityLevel,
        val activityBasis: ActivityBasis,
        val program: TrainingProgram,
        val registered: Map<LocalDate, List<TrainingDayResolver.RegisteredWorkout>> = emptyMap(),
        val checkIns: Map<LocalDate, TrainingDayResolver.CheckIn> = emptyMap(),
        /** Correction already applied by the adaptive engine to the profile target (1.0 = none). */
        val adaptiveRatio: Double = 1.0,
    )

    data class Day(
        val date: LocalDate,
        val resolution: TrainingDayResolver.Resolution,
        val energy: DayEnergyEngine.DayEnergy,
        /** Target after the adaptive correction and the BMR floor, before any extra-meal recovery. */
        val baseTargets: NutritionBusinessValidator.Targets,
        /** Expenditure of the day: the calorie ceiling for deficit goals, null for the other goals. */
        val ceilingKcal: Double?,
    )

    fun everydayLevel(level: ActivityLevel, basis: ActivityBasis): ActivityLevel = when (basis) {
        ActivityBasis.LEGACY_INCLUDES_TRAINING -> DayEnergyEngine.everydayLevelFromLegacy(level)
        ActivityBasis.EVERYDAY_ONLY -> level
    }

    /** @return null when program mode does not apply (empty program) or a day cannot be computed. */
    fun plan(dates: List<LocalDate>, inputs: Inputs): List<Day>? {
        if (inputs.program.isEmpty || dates.isEmpty()) return null
        val multiplier = everydayLevel(inputs.activityLevel, inputs.activityBasis).multiplier
        val weekly = inputs.program.toWeeklyProgram()
        val days = DayEnergyEngine.forDates(dates, inputs.bmrKcal, inputs.weightKg, multiplier, inputs.goal) { date ->
            TrainingDayResolver.resolve(
                date = date,
                checkIn = inputs.checkIns[date],
                registered = inputs.registered[date].orEmpty(),
                program = weekly,
            )
        } ?: return null
        val ratio = inputs.adaptiveRatio.takeIf { it.isFinite() && it in MIN_RATIO..MAX_RATIO } ?: 1.0
        val deficitGoal = inputs.goal == Goal.WEIGHT_LOSS || inputs.goal == Goal.RECOMPOSITION
        return days.map { plan ->
            val kcal = max(plan.energy.targetKcal * ratio, inputs.bmrKcal)
            val macros = LocalCalculationEngine.calculateMacrosForTarget(kcal, inputs.weightKg, inputs.goal)
            Day(
                date = plan.date,
                resolution = plan.resolution,
                energy = plan.energy,
                baseTargets = NutritionBusinessValidator.Targets(kcal, macros.proteinG, macros.carbsG, macros.fatG),
                ceilingKcal = plan.energy.tdeeKcal.takeIf { deficitGoal },
            )
        }
    }

    fun averageBaseKcal(days: List<Day>): Double? = days.takeIf { it.isNotEmpty() }?.map { it.baseTargets.kcal }?.average()

    private const val MIN_RATIO = 0.5
    private const val MAX_RATIO = 1.5
}
