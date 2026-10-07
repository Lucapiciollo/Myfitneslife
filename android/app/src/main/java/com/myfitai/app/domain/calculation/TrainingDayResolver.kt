package com.myfitai.app.domain.calculation

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Decides whether a date is a training day and with which workout.
 *
 * Priority, highest first:
 *  1. the user's own check-in for that day;
 *  2. workouts or rest days the user registered for that exact date;
 *  3. the recurring weekly program;
 *  4. rest, which is also what the app assumed before a program existed.
 */
object TrainingDayResolver {
    enum class Source { MANUAL_CHECK_IN, REGISTERED_WORKOUT, WEEKLY_PROGRAM, DEFAULT_REST }

    data class Resolution(val session: TrainingSession?, val source: Source) {
        val isTraining: Boolean get() = session != null
    }

    /** Manual per-day check-in; durations and intensity fall back to the weekly program, then to defaults. */
    data class CheckIn(
        val isTraining: Boolean,
        val durationMinutes: Int? = null,
        val intensity: DailyActivityCheckInEngine.Intensity? = null,
    )

    /** A workout (or explicit rest day) the user registered for a date. */
    data class RegisteredWorkout(
        val isRestDay: Boolean,
        val durationMinutes: Int? = null,
        val startMinutes: Int? = null,
    )

    data class WeeklyProgram(val sessions: Map<DayOfWeek, TrainingSession>) {
        fun sessionFor(date: LocalDate): TrainingSession? = sessions[date.dayOfWeek]
        val trainingDaysPerWeek: Int get() = sessions.size
    }

    fun resolve(
        date: LocalDate,
        checkIn: CheckIn? = null,
        registered: List<RegisteredWorkout> = emptyList(),
        program: WeeklyProgram? = null,
    ): Resolution {
        val programmed = program?.sessionFor(date)
        if (checkIn != null) {
            return if (!checkIn.isTraining) {
                Resolution(null, Source.MANUAL_CHECK_IN)
            } else {
                Resolution(
                    session(
                        duration = checkIn.durationMinutes ?: programmed?.durationMinutes,
                        intensity = checkIn.intensity ?: programmed?.intensity,
                        start = programmed?.startMinutes,
                    ),
                    Source.MANUAL_CHECK_IN,
                )
            }
        }
        val workouts = registered.filter { !it.isRestDay }
        if (workouts.isNotEmpty()) {
            // An explicit workout beats a rest-day note for the same date: it is the more specific fact.
            val totalMinutes = workouts.sumOf { it.durationMinutes ?: programmed?.durationMinutes ?: DEFAULT_DURATION_MINUTES }
            return Resolution(
                session(
                    duration = totalMinutes,
                    intensity = programmed?.intensity,
                    start = workouts.mapNotNull { it.startMinutes }.minOrNull() ?: programmed?.startMinutes,
                ),
                Source.REGISTERED_WORKOUT,
            )
        }
        if (registered.any { it.isRestDay }) return Resolution(null, Source.REGISTERED_WORKOUT)
        if (programmed != null) return Resolution(programmed, Source.WEEKLY_PROGRAM)
        return Resolution(null, Source.DEFAULT_REST)
    }

    private fun session(duration: Int?, intensity: DailyActivityCheckInEngine.Intensity?, start: Int?) = TrainingSession(
        durationMinutes = (duration ?: DEFAULT_DURATION_MINUTES)
            .coerceIn(TrainingSession.MIN_DURATION_MINUTES, TrainingSession.MAX_DURATION_MINUTES),
        intensity = intensity ?: DEFAULT_INTENSITY,
        startMinutes = start?.takeIf { it in 0 until 24 * 60 },
    )

    const val DEFAULT_DURATION_MINUTES = 60
    val DEFAULT_INTENSITY = DailyActivityCheckInEngine.Intensity.MODERATE
}
