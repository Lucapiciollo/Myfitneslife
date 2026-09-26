package com.myfitai.app.domain.progress

import java.time.Instant
import java.time.LocalDate
import java.time.Period
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
        asOfDate: LocalDate = LocalDate.now(zoneId),
    ): FilteredProgressSeries = filter(
        points = points,
        range = when (rangeIndex) {
            0 -> Period.ofMonths(1)
            1 -> Period.ofMonths(3)
            2 -> Period.ofMonths(6)
            else -> Period.ofYears(1)
        },
        zoneId = zoneId,
        asOfDate = asOfDate,
    )

    fun filter(
        points: List<ProgressSeriesPoint>,
        range: Period,
        zoneId: ZoneId,
        asOfDate: LocalDate = LocalDate.now(zoneId),
    ): FilteredProgressSeries {
        val normalized = normalize(points)
        if (normalized.isEmpty()) return FilteredProgressSeries(emptyList(), null, null)
        val fromDate = asOfDate.minus(range)
        val from = fromDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val until = asOfDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val filtered = normalized.filter { it.timestamp >= from && it.timestamp < until }
        return FilteredProgressSeries(
            points = filtered,
            value = filtered.lastOrNull()?.value,
            delta = if (filtered.size >= 2) filtered.last().value - filtered.first().value else null,
        )
    }
}
