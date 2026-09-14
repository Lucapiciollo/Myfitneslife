package com.myfitai.app.domain.progress

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ProgressSeriesPoint(val timestamp: Long, val value: Float)

data class FilteredProgressSeries(
    val points: List<ProgressSeriesPoint>,
    val value: Float?,
    val delta: Float?,
)

/** Pure range/delta logic shared by progress UI tests and the ViewModel. */
object ProgressSeriesEngine {
    fun normalize(points: List<ProgressSeriesPoint>): List<ProgressSeriesPoint> =
        points.filter { it.value.isFinite() }.sortedWith(compareBy<ProgressSeriesPoint> { it.timestamp }.thenBy { it.value })

    fun filter(
        points: List<ProgressSeriesPoint>,
        rangeIndex: Int,
        zoneId: ZoneId,
    ): FilteredProgressSeries {
        val normalized = normalize(points)
        if (normalized.isEmpty()) return FilteredProgressSeries(emptyList(), null, null)
        val latestDate = Instant.ofEpochMilli(normalized.last().timestamp).atZone(zoneId).toLocalDate()
        val fromDate = when (rangeIndex) {
            0 -> latestDate.minusMonths(1)
            1 -> latestDate.minusMonths(3)
            2 -> latestDate.minusMonths(6)
            else -> latestDate.minusYears(1)
        }
        val from = fromDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val filtered = normalized.filter { it.timestamp >= from }
        return FilteredProgressSeries(
            points = filtered,
            value = filtered.lastOrNull()?.value,
            delta = if (filtered.size >= 2) filtered.last().value - filtered.first().value else null,
        )
    }
}
