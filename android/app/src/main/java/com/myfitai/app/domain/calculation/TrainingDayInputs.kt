package com.myfitai.app.domain.calculation

import com.myfitai.app.data.local.entity.DailyActivityCheckInEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Converts stored workouts and check-ins into the inputs of [TrainingDayResolver]. Shared by plan generation, Home and Food. */
object TrainingDayInputs {
    fun registeredWorkouts(
        workouts: List<WorkoutEntity>,
        zone: ZoneId,
    ): Map<LocalDate, List<TrainingDayResolver.RegisteredWorkout>> = workouts
        .groupBy { Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(zone).toLocalDate() }
        .mapValues { (_, items) ->
            items.map { w ->
                val time = Instant.ofEpochMilli(w.startedAtEpochMillis).atZone(zone).toLocalTime()
                TrainingDayResolver.RegisteredWorkout(
                    isRestDay = w.isRestDay,
                    durationMinutes = w.durationMinutes,
                    startMinutes = if (w.isRestDay) null else time.hour * 60 + time.minute,
                )
            }
        }

    /** Only REST and PLANNED_WORKOUT check-ins carry a decision; anything else is ignored. */
    fun checkIns(
        entries: List<DailyActivityCheckInEntity>,
        dates: Collection<LocalDate>,
    ): Map<LocalDate, TrainingDayResolver.CheckIn> {
        val wanted = dates.associateBy { it.toEpochDay() }
        return entries.mapNotNull { entry ->
            val date = wanted[entry.dateEpochDay] ?: return@mapNotNull null
            val checkIn = when (entry.status) {
                "REST" -> TrainingDayResolver.CheckIn(isTraining = false)
                "PLANNED_WORKOUT" -> TrainingDayResolver.CheckIn(
                    isTraining = true,
                    durationMinutes = entry.durationMinutes,
                    intensity = DailyActivityCheckInEngine.Intensity.entries.firstOrNull { it.name == entry.intensity },
                )
                else -> return@mapNotNull null
            }
            date to checkIn
        }.toMap()
    }
}