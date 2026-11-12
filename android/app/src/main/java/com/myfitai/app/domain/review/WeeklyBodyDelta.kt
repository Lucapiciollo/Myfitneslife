package com.myfitai.app.domain.review

import kotlin.math.roundToInt

/** Weekly change of one body metric, computed from timestamped readings regardless of the order they are supplied in. */
object WeeklyBodyDelta {
    /** Largest distance between the single reading of the week and its reference before the week. */
    const val MAX_REFERENCE_GAP_DAYS = 14
    private const val DAY_MS = 86_400_000L
    private const val MAX_REFERENCE_GAP_MILLIS = MAX_REFERENCE_GAP_DAYS * DAY_MS

    data class Reading(val measuredAtEpochMillis: Long, val value: Float)

    /** [value] is the change between the two readings actually used, [spanDays] the real distance between them. */
    data class Delta(val value: Float, val fromEpochMillis: Long, val toEpochMillis: Long) {
        val spanDays: Int get() = ((toEpochMillis - fromEpochMillis).toDouble() / DAY_MS).roundToInt()
    }

    /**
     * - 2+ readings in the week: latest minus earliest reading of the week.
     * - 1 reading: that reading minus the latest reading before the week, only if it is at most
     *   [MAX_REFERENCE_GAP_DAYS] days earlier; an older reference gives no weekly delta.
     * - none, or all readings at the same instant (no direction): null.
     * The value is rounded to one decimal.
     */
    fun describe(week: List<Reading>, previous: List<Reading>): Delta? {
        val ordered = week.sortedBy { it.measuredAtEpochMillis }
        return when {
            ordered.size >= 2 -> {
                val first = ordered.first()
                val last = ordered.last()
                if (first.measuredAtEpochMillis == last.measuredAtEpochMillis) return null
                Delta(round(last.value - first.value), first.measuredAtEpochMillis, last.measuredAtEpochMillis)
            }
            ordered.size == 1 -> {
                val reference = previous.maxByOrNull { it.measuredAtEpochMillis } ?: return null
                val gap = ordered.first().measuredAtEpochMillis - reference.measuredAtEpochMillis
                if (gap > MAX_REFERENCE_GAP_MILLIS) return null
                Delta(round(ordered.first().value - reference.value), reference.measuredAtEpochMillis, ordered.first().measuredAtEpochMillis)
            }
            else -> null
        }
    }

    fun compute(week: List<Reading>, previous: List<Reading>): Float? = describe(week, previous)?.value

    private fun round(raw: Float): Float = (raw * 10f).roundToInt() / 10f
}
