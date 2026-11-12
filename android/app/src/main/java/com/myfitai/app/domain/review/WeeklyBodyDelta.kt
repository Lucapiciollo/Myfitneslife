package com.myfitai.app.domain.review

import com.myfitai.app.domain.time.CalendarDays
import java.time.ZoneId
import kotlin.math.roundToInt

/** Weekly change of one body metric, computed from timestamped readings regardless of the order they are supplied in. */
object WeeklyBodyDelta {
    /** Largest distance (calendar days, local zone) between the single reading of the week and its reference before the week. */
    const val MAX_REFERENCE_GAP_DAYS = 14

    data class Reading(val measuredAtEpochMillis: Long, val value: Float)

    /**
     * [value] is the change between the two readings actually used, [spanDays] the real distance between them
     * in local calendar days (not milliseconds / 86 400 000, which drifts by an hour across DST changes).
     */
    data class Delta(
        val value: Float,
        val fromEpochMillis: Long,
        val toEpochMillis: Long,
        val spanDays: Int = CalendarDays.between(fromEpochMillis, toEpochMillis).toInt(),
    )

    /**
     * - 2+ readings in the week: latest minus earliest reading of the week.
     * - 1 reading: that reading minus the latest reading before the week, only if it is at most
     *   [MAX_REFERENCE_GAP_DAYS] calendar days earlier; an older reference gives no weekly delta.
     * - none, or all readings at the same instant (no direction): null.
     * The value is rounded to one decimal.
     */
    fun describe(week: List<Reading>, previous: List<Reading>, zone: ZoneId = ZoneId.systemDefault()): Delta? {
        val ordered = week.sortedBy { it.measuredAtEpochMillis }
        return when {
            ordered.size >= 2 -> {
                val first = ordered.first()
                val last = ordered.last()
                if (first.measuredAtEpochMillis == last.measuredAtEpochMillis) return null
                Delta(
                    round(last.value - first.value), first.measuredAtEpochMillis, last.measuredAtEpochMillis,
                    CalendarDays.between(first.measuredAtEpochMillis, last.measuredAtEpochMillis, zone).toInt(),
                )
            }
            ordered.size == 1 -> {
                val reference = previous.maxByOrNull { it.measuredAtEpochMillis } ?: return null
                val gapDays = CalendarDays.between(reference.measuredAtEpochMillis, ordered.first().measuredAtEpochMillis, zone)
                if (gapDays > MAX_REFERENCE_GAP_DAYS) return null
                Delta(
                    round(ordered.first().value - reference.value), reference.measuredAtEpochMillis, ordered.first().measuredAtEpochMillis,
                    gapDays.toInt(),
                )
            }
            else -> null
        }
    }

    fun compute(week: List<Reading>, previous: List<Reading>, zone: ZoneId = ZoneId.systemDefault()): Float? =
        describe(week, previous, zone)?.value

    private fun round(raw: Float): Float = (raw * 10f).roundToInt() / 10f
}
