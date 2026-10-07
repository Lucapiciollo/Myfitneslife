package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
import java.time.DayOfWeek
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The recurring weekly training program of a profile: which weekdays include a workout and the usual
 * shape of that workout. Pure model; persistence lives in `TrainingProgramPreferences`.
 *
 * An empty program means "no training days set", which the app treats as rest every day.
 */
data class TrainingProgram(
    val days: Set<DayOfWeek> = emptySet(),
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
    val intensity: Intensity = DEFAULT_INTENSITY,
    /** Usual start time as minutes from midnight; optional. */
    val startMinutes: Int? = null,
) {
    init {
        require(durationMinutes in TrainingSession.MIN_DURATION_MINUTES..TrainingSession.MAX_DURATION_MINUTES) { "TRAINING_DURATION_INVALID" }
        require(startMinutes == null || startMinutes in 0 until MINUTES_PER_DAY) { "TRAINING_START_INVALID" }
    }

    val isEmpty: Boolean get() = days.isEmpty()

    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val daysMask: Int get() = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

    fun toWeeklyProgram(): TrainingDayResolver.WeeklyProgram = TrainingDayResolver.WeeklyProgram(
        days.associateWith { TrainingSession(durationMinutes, intensity, startMinutes) },
    )

    fun summary(): String {
        if (isEmpty) return "Nessun giorno di allenamento impostato"
        val parts = mutableListOf(
            days.sorted().joinToString(", ") { dayLabel(it) },
            "$durationMinutes min",
            intensityLabel(intensity),
        )
        startMinutes?.let { parts += "%02d:%02d".format(it / 60, it % 60) }
        return parts.joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("days", JSONArray(days.sorted().map { it.name }))
        .put("durationMinutes", durationMinutes)
        .put("intensity", intensity.name)
        .apply { startMinutes?.let { put("startMinutes", it) } }

    companion object {
        const val DEFAULT_DURATION_MINUTES = 60
        val DEFAULT_INTENSITY = Intensity.MODERATE
        val DURATION_OPTIONS = listOf(30, 45, 60, 75, 90, 120)
        private const val MINUTES_PER_DAY = 24 * 60

        /** Builds a valid program from untrusted values (preferences, backups): clamps instead of failing. */
        fun sanitized(days: Set<DayOfWeek>, durationMinutes: Int?, intensityName: String?, startMinutes: Int?): TrainingProgram =
            TrainingProgram(
                days = days,
                durationMinutes = (durationMinutes ?: DEFAULT_DURATION_MINUTES)
                    .coerceIn(TrainingSession.MIN_DURATION_MINUTES, TrainingSession.MAX_DURATION_MINUTES),
                intensity = Intensity.entries.firstOrNull { it.name == intensityName } ?: DEFAULT_INTENSITY,
                startMinutes = startMinutes?.takeIf { it in 0 until MINUTES_PER_DAY },
            )

        fun daysFromMask(mask: Int): Set<DayOfWeek> =
            DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()

        /** @param startMinutes negative means "not set", matching how it is stored. */
        fun fromStored(mask: Int, durationMinutes: Int, intensityName: String?, startMinutes: Int): TrainingProgram =
            sanitized(daysFromMask(mask), durationMinutes, intensityName, startMinutes.takeIf { it >= 0 })

        /** @return null when the object is absent, so restoring an older backup leaves the profile untouched. */
        fun fromJson(json: JSONObject?): TrainingProgram? {
            json ?: return null
            val names = json.optJSONArray("days")
            val days = buildSet {
                if (names != null) for (index in 0 until names.length()) {
                    DayOfWeek.entries.firstOrNull { it.name == names.optString(index) }?.let(::add)
                }
            }
            return sanitized(
                days = days,
                durationMinutes = if (json.has("durationMinutes")) json.optInt("durationMinutes") else null,
                intensityName = json.optString("intensity").takeIf { it.isNotBlank() },
                startMinutes = if (json.has("startMinutes") && !json.isNull("startMinutes")) json.optInt("startMinutes") else null,
            )
        }

        fun dayLabel(day: DayOfWeek): String = when (day) {
            DayOfWeek.MONDAY -> "Lun"
            DayOfWeek.TUESDAY -> "Mar"
            DayOfWeek.WEDNESDAY -> "Mer"
            DayOfWeek.THURSDAY -> "Gio"
            DayOfWeek.FRIDAY -> "Ven"
            DayOfWeek.SATURDAY -> "Sab"
            DayOfWeek.SUNDAY -> "Dom"
        }

        fun intensityLabel(intensity: Intensity): String = when (intensity) {
            Intensity.LIGHT -> "leggera"
            Intensity.MODERATE -> "moderata"
            Intensity.HARD -> "intensa"
        }.lowercase(Locale.ITALIAN)
    }
}
