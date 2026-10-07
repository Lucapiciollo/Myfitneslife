package com.myfitai.app.domain.calculation

import com.myfitai.app.data.profile.TrainingProgramPreferences
import com.myfitai.app.data.repository.DailyActivityCheckInRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * Per-day energy for the screens (Home, Food, weekly expectation) from the same engine that generates the plan.
 * Returns null whenever program mode does not apply, so callers keep the legacy single-target behaviour.
 */
class DayEnergyProvider(
    private val calculations: ProfileCalculationService,
    private val profiles: UserProfileRepository,
    private val workouts: WorkoutRepository,
    private val checkIns: DailyActivityCheckInRepository,
    private val program: TrainingProgramPreferences,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun day(profileId: Long, date: LocalDate): TrainingEnergyPlanner.Day? = days(profileId, listOf(date))?.firstOrNull()

    suspend fun days(profileId: Long, dates: List<LocalDate>): List<TrainingEnergyPlanner.Day>? {
        if (dates.isEmpty()) return null
        val trainingProgram = program.get(profileId)
        if (trainingProgram.isEmpty) return null
        val profile = profiles.get(profileId) ?: return null
        val snapshot = calculations.profileSnapshot(profileId) ?: return null
        val bmr = snapshot.calculation.bmrKcal ?: return null
        val weightKg = snapshot.latestWeightKg?.toDouble() ?: return null
        val goal = ProfileCalculationMapper.goal(profile.goal) ?: return null
        val level = ProfileCalculationMapper.activity(profile.activityLevel) ?: return null
        val zoneId = zone()
        val from = dates.min().atStartOfDay(zoneId).toInstant().toEpochMilli()
        val to = dates.max().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
        return TrainingEnergyPlanner.plan(
            dates = dates,
            inputs = TrainingEnergyPlanner.Inputs(
                bmrKcal = bmr,
                weightKg = weightKg,
                goal = goal,
                activityLevel = level,
                activityBasis = program.activityBasis(profileId),
                program = trainingProgram,
                registered = TrainingDayInputs.registeredWorkouts(workouts.between(profileId, from, to).first(), zoneId),
                checkIns = TrainingDayInputs.checkIns(checkIns.all(profileId), dates),
            ),
        )
    }

    companion object {
        /** One line for the Home calories card: what kind of day it is and what it costs. */
        fun describe(day: TrainingEnergyPlanner.Day): String {
            val tdee = String.format(Locale.ITALIAN, "%,d", Math.round(day.energy.tdeeKcal).toInt())
            val session = day.resolution.session
            val origin = when (day.resolution.source) {
                TrainingDayResolver.Source.MANUAL_CHECK_IN -> " (check-in di oggi)"
                TrainingDayResolver.Source.REGISTERED_WORKOUT -> " (registrato)"
                else -> ""
            }
            return if (session == null) {
                "Giorno di riposo$origin · TDEE $tdee kcal"
            } else {
                "Allenamento ${session.durationMinutes} min ${TrainingProgram.intensityLabel(session.intensity)}$origin · +${day.energy.exerciseKcal} kcal · TDEE $tdee kcal"
            }
        }
    }
}