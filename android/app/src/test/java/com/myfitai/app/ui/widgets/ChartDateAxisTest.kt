package com.myfitai.app.ui.widgets

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDateAxisTest {
    private val zone = ZoneId.of("Europe/Rome")

    private fun millis(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun assertValid(texts: List<String>, indices: List<Int>, max: Int) {
        assertTrue("al massimo N etichette", indices.size <= minOf(texts.size, max))
        assertEquals("indici crescenti e unici", indices.sorted().distinct(), indices)
        assertTrue("indici dentro la serie", indices.all { it in texts.indices })
        val shown = indices.map { texts[it] }
        assertEquals("nessun testo ripetuto: $shown", shown.distinct(), shown)
    }

    @Test
    fun twoPointsShowAtMostTwoDistinctLabels() {
        val texts = listOf("12/09", "27/09")
        val indices = ChartDateAxis.selectIndices(texts, 5)
        assertEquals(listOf(0, 1), indices)
        assertValid(texts, indices, 5)
    }

    @Test
    fun singlePointShowsOneLabel() {
        assertEquals(listOf(0), ChartDateAxis.selectIndices(listOf("12/09"), 5))
    }

    @Test
    fun emptyAndZeroMaxShowNothing() {
        assertTrue(ChartDateAxis.selectIndices(emptyList(), 5).isEmpty())
        assertTrue(ChartDateAxis.selectIndices(listOf("12/09"), 0).isEmpty())
    }

    @Test
    fun repeatedDaysAreNotRepeatedOnTheAxis() {
        val texts = listOf("12/09", "12/09", "12/09", "12/09", "13/09")
        val indices = ChartDateAxis.selectIndices(texts, 5)
        assertValid(texts, indices, 5)
        assertEquals(listOf("12/09", "13/09"), indices.map { texts[it] })
    }

    @Test
    fun manyPointsAreCappedAndKeepFirstAndLast() {
        val texts = (1..40).map { "%02d/10".format((it % 28) + 1) + "#$it" }
        val indices = ChartDateAxis.selectIndices(texts, 5)
        assertValid(texts, indices, 5)
        assertEquals(5, indices.size)
        assertEquals(0, indices.first())
        assertEquals(texts.lastIndex, indices.last())
    }

    @Test
    fun everyPointCountAndMaximumStayValid() {
        for (n in 0..60) {
            val texts = (0 until n).map { "d$it" }
            for (max in 0..8) assertValid(texts, ChartDateAxis.selectIndices(texts, max), max)
        }
    }

    @Test
    fun formatUsesTwoDigitYearOnlyWhenPeriodExceedsOneYear() {
        val short = ChartDateAxis.formatTimestamps(listOf(millis("2026-09-12"), millis("2026-09-27")), zone)
        assertEquals(listOf("12/09", "27/09"), short)
        val exactlyOneYear = ChartDateAxis.formatTimestamps(listOf(millis("2025-09-12"), millis("2026-09-12")), zone)
        assertEquals(listOf("12/09", "12/09"), exactlyOneYear)
        val long = ChartDateAxis.formatTimestamps(listOf(millis("2025-09-01"), millis("2026-09-27")), zone)
        assertEquals(listOf("01/09/25", "27/09/26"), long)
    }

    @Test
    fun labelsForTwoRealPointsHaveNoDuplicates() {
        val labels = ChartDateAxis.labels(listOf(millis("2026-09-12"), millis("2026-09-12")), zone, 5)
        assertEquals(listOf(ChartDateAxis.Label(0, "12/09")), labels)
        val two = ChartDateAxis.labels(listOf(millis("2026-09-12"), millis("2026-09-20")), zone, 5)
        assertEquals(listOf("12/09", "20/09"), two.map { it.text })
    }
}
