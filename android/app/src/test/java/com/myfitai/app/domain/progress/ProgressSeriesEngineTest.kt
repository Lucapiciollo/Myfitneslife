package com.myfitai.app.domain.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ProgressSeriesEngineTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 9, 14)

    @Test
    fun emptyAndSinglePoint_areSafeAndHaveNoDelta() {
        val empty = ProgressSeriesEngine.filter(emptyList(), 2, zone)
        val single = ProgressSeriesEngine.filter(listOf(point(today, 89f)), 2, zone)

        assertTrue(empty.points.isEmpty())
        assertNull(empty.value)
        assertNull(empty.delta)
        assertEquals(89f, single.value)
        assertNull(single.delta)
    }

    @Test
    fun ranges_useLatestPointAndIgnoreMissingNonFiniteValues() {
        val points = listOf(
            point(today.minusMonths(7), 95f),
            point(today.minusMonths(5), 93f),
            point(today.minusMonths(2), 91f),
            point(today.minusDays(1), 89f),
            ProgressSeriesPoint(today.toEpochDay() * 86_400_000L, Float.NaN),
        )

        assertEquals(listOf(93f, 91f, 89f), ProgressSeriesEngine.filter(points, 2, zone).points.map { it.value })
        assertEquals(listOf(91f, 89f), ProgressSeriesEngine.filter(points, 1, zone).points.map { it.value })
        assertEquals(listOf(89f), ProgressSeriesEngine.filter(points, 0, zone).points.map { it.value })
        assertEquals(-4f, ProgressSeriesEngine.filter(points, 2, zone).delta)
    }

    @Test
    fun duplicateDates_areOrderedDeterministicallyAndOneYearKeepsAllHistory() {
        val duplicate = listOf(point(today, 90f), point(today, 89f), point(today.minusMonths(13), 99f))
        val normalized = ProgressSeriesEngine.normalize(duplicate)

        assertEquals(listOf(99f, 89f, 90f), normalized.map { it.value })
        assertEquals(listOf(89f, 90f), ProgressSeriesEngine.filter(duplicate, 3, zone).points.map { it.value })
    }

    private fun point(date: LocalDate, value: Float) =
        ProgressSeriesPoint(date.atStartOfDay(zone).toInstant().toEpochMilli(), value)
}
