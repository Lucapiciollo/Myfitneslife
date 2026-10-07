package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.LocalCalculationEngine.ActivityLevel
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** One planned workout. Durations are clamped by the resolver; the engine only accepts sane values. */
data class TrainingSession(
    val durationMinutes: Int,
    val intensity: DailyActivityCheckInEngine.Intensity,
    /** Minutes from midnight; null when the usual time is unknown. */
    val startMinutes: Int? = null,
) {
    init {
        require(durationMinutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) { "TRAINING_DURATION_INVALID" }
        require(startMinutes == null || startMinutes in 0 until MINUTES_PER_DAY) { "TRAINING_START_INVALID" }
    }

    companion object {
        const val MIN_DURATION_MINUTES = 10
        const val MAX_DURATION_MINUTES = 240
        private const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * Deterministic daily energy model: the deficit (or surplus) is always the same percentage of THAT day's
 * expenditure, so rest days get fewer calories than training days.
 *
 *   day expenditure = BMR x everyday-activity factor (workouts excluded) + workout cost
 *   day target      = goal factor x day expenditure, never below the BMR
 *
 * Protein and fat stay per kilogram of body weight; carbohydrates absorb the difference.
 * Nothing is delegated to the LLM and nothing here is persisted.
 */
object DayEnergyEngine {
    enum class DayKind { REST, TRAINING }

    data class Input(
        val bmrKcal: Double,
        val weightKg: Double,
        /** Everyday activity (job, steps) WITHOUT scheduled workouts. */
        val lifestyleMultiplier: Double,
        val goal: Goal,
        val session: TrainingSession?,
    )

    data class DayEnergy(
        val kind: DayKind,
        val session: TrainingSession?,
        val bmrKcal: Double,
        val lifestyleKcal: Double,
        val exerciseKcal: Int,
        /** Total expenditure of the day; also the natural calorie ceiling for deficit goals. */
        val tdeeKcal: Double,
        val targetKcal: Double,
        /** True when the safety minimum replaced a lower computed target. */
        val floorApplied: Boolean,
        val proteinG: Double,
        val fatG: Double,
        val carbsG: Double,
    )

    data class DayPlan(
        val date: LocalDate,
        val resolution: TrainingDayResolver.Resolution,
        val energy: DayEnergy,
    )

    /** Net MET above resting: 1 MET is already part of the BMR, so it is not counted twice. */
    fun netMet(intensity: DailyActivityCheckInEngine.Intensity): Double = when (intensity) {
        DailyActivityCheckInEngine.Intensity.LIGHT -> 2.5
        DailyActivityCheckInEngine.Intensity.MODERATE -> 4.0
        DailyActivityCheckInEngine.Intensity.HARD -> 6.0
    }

    fun exerciseKcal(weightKg: Double, session: TrainingSession?): Int {
        if (session == null || !weightKg.isFinite() || weightKg <= 0.0) return 0
        return (netMet(session.intensity) * weightKg * session.durationMinutes / 60.0).roundToInt()
    }

    /**
     * Profiles saved before the workout schedule existed chose a level that already counted training.
     * One step down approximates the everyday-only level; the user confirms it before the first plan.
     */
    fun everydayLevelFromLegacy(level: ActivityLevel): ActivityLevel = when (level) {
        ActivityLevel.SEDENTARY, ActivityLevel.LIGHT -> ActivityLevel.SEDENTARY
        ActivityLevel.MODERATE -> ActivityLevel.LIGHT
        ActivityLevel.VERY_ACTIVE -> ActivityLevel.MODERATE
        ActivityLevel.EXTREME -> ActivityLevel.VERY_ACTIVE
    }

    /** @return null when the inputs cannot support a trustworthy number. */
    fun calculate(input: Input): DayEnergy? {
        if (!input.bmrKcal.isFinite() || input.bmrKcal <= 0.0) return null
        if (!input.weightKg.isFinite() || input.weightKg <= 0.0) return null
        if (!input.lifestyleMultiplier.isFinite() || input.lifestyleMultiplier !in MIN_MULTIPLIER..MAX_MULTIPLIER) return null

        val lifestyleKcal = input.bmrKcal * input.lifestyleMultiplier
        val exercise = exerciseKcal(input.weightKg, input.session)
        val tdee = lifestyleKcal + exercise
        val computed = tdee * LocalCalculationEngine.goalEnergyFactor(input.goal)
        val target = max(computed, input.bmrKcal)
        val macros = LocalCalculationEngine.calculateMacrosForTarget(target, input.weightKg, input.goal)
        return DayEnergy(
            kind = if (input.session != null) DayKind.TRAINING else DayKind.REST,
            session = input.session,
            bmrKcal = input.bmrKcal,
            lifestyleKcal = lifestyleKcal,
            exerciseKcal = exercise,
            tdeeKcal = tdee,
            targetKcal = target,
            floorApplied = computed < input.bmrKcal,
            proteinG = macros.proteinG,
            fatG = macros.fatG,
            carbsG = macros.carbsG,
        )
    }

    /** @return null if any day cannot be computed, so a partial week is never presented as complete. */
    fun forDates(
        dates: List<LocalDate>,
        bmrKcal: Double,
        weightKg: Double,
        lifestyleMultiplier: Double,
        goal: Goal,
        resolutionFor: (LocalDate) -> TrainingDayResolver.Resolution,
    ): List<DayPlan>? = dates.map { date ->
        val resolution = resolutionFor(date)
        val energy = calculate(Input(bmrKcal, weightKg, lifestyleMultiplier, goal, resolution.session)) ?: return null
        DayPlan(date, resolution, energy)
    }

    fun averageTargetKcal(plans: List<DayPlan>): Double? =
        plans.takeIf { it.isNotEmpty() }?.map { it.energy.targetKcal }?.average()

    fun averageTdeeKcal(plans: List<DayPlan>): Double? =
        plans.takeIf { it.isNotEmpty() }?.map { it.energy.tdeeKcal }?.average()

    private const val MIN_MULTIPLIER = 1.0
    private const val MAX_MULTIPLIER = 2.5
}
