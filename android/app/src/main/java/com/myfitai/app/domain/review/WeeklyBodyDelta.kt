package com.myfitai.app.domain.review

import kotlin.math.roundToInt

/** Weekly change of one body metric, computed from timestamped readings regardless of the order they are supplied in. */
object WeeklyBodyDelta {
    data class Reading(val measuredAtEpochMillis: Long, val value: Float)

    /**
     * - 2+ readings in the week: latest minus earliest reading of the week.
     * - 1 reading: that reading minus the latest reading before the week, if any.
     * - none, or all readings at the same instant (no direction): null.
     * The result is rounded to one decimal.
     */
    fun compute(week: List<Reading>, previous: List<Reading>): Float? {
        val ordered = week.sortedBy { it.measuredAtEpochMillis }
        val raw = when {
            ordered.size >= 2 -> {
                if (ordered.first().measuredAtEpochMillis == ordered.last().measuredAtEpochMillis) return null
                ordered.last().value - ordered.first().value
            }
            ordered.size == 1 -> {
                val reference = previous.maxByOrNull { it.measuredAtEpochMillis } ?: return null
                ordered.first().value - reference.value
            }
            else -> return null
        }
        return (raw * 10f).roundToInt() / 10f
    }
}
