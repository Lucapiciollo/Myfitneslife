package com.myfitai.app.domain.review

import com.myfitai.app.domain.review.WeeklyBodyDelta.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyBodyDeltaTest {
    private val monday = 1_000_000L

    @Test
    fun descendingReadingsGiveNegativeDeltaWhenWeightDrops() {
        // DAO order: newest first. 90 kg at the start of the week, 89 kg at the end.
        val week = listOf(Reading(monday + 5_000, 89f), Reading(monday + 1_000, 90f))

        assertEquals(-1.0f, WeeklyBodyDelta.compute(week, emptyList())!!, 0f)
    }

    @Test
    fun resultDoesNotDependOnInputOrder() {
        val asc = listOf(Reading(monday + 1_000, 90f), Reading(monday + 3_000, 90.6f), Reading(monday + 5_000, 91f))

        assertEquals(1.0f, WeeklyBodyDelta.compute(asc, emptyList())!!, 0f)
        assertEquals(1.0f, WeeklyBodyDelta.compute(asc.reversed(), emptyList())!!, 0f)
        assertEquals(1.0f, WeeklyBodyDelta.compute(listOf(asc[1], asc[2], asc[0]), emptyList())!!, 0f)
    }

    @Test
    fun deltaUsesFirstAndLastOfTheWeekIgnoringPreviousReadings() {
        val week = listOf(Reading(monday + 5_000, 89f), Reading(monday + 1_000, 90f))
        val previous = listOf(Reading(monday - 1_000, 70f))

        assertEquals(-1.0f, WeeklyBodyDelta.compute(week, previous)!!, 0f)
    }

    @Test
    fun deltaIsRoundedToOneDecimal() {
        val week = listOf(Reading(monday + 5_000, 89.74f), Reading(monday + 1_000, 90.1f))

        assertEquals(-0.4f, WeeklyBodyDelta.compute(week, emptyList())!!, 0f)
    }

    @Test
    fun floatNoiseDoesNotLeakIntoTheDelta() {
        val week = listOf(Reading(monday + 1_000, 19.9f), Reading(monday + 5_000, 19.1f))

        assertEquals(-0.8f, WeeklyBodyDelta.compute(week, emptyList())!!, 0f)
    }

    @Test
    fun singleReadingIsComparedWithTheLatestReadingBeforeTheWeek() {
        val week = listOf(Reading(monday + 2_000, 88.5f))
        // Newest first, like the DAOs: the latest previous reading (89.0) is the reference, not the oldest (95.0).
        val previous = listOf(Reading(monday - 1_000, 89f), Reading(monday - 9_000, 95f))

        assertEquals(-0.5f, WeeklyBodyDelta.compute(week, previous)!!, 0f)
        assertEquals(-0.5f, WeeklyBodyDelta.compute(week, previous.reversed())!!, 0f)
    }

    @Test
    fun singleReadingWithoutPreviousHistoryHasNoDelta() {
        assertNull(WeeklyBodyDelta.compute(listOf(Reading(monday + 2_000, 88.5f)), emptyList()))
    }

    @Test
    fun noReadingsInTheWeekHasNoDelta() {
        assertNull(WeeklyBodyDelta.compute(emptyList(), emptyList()))
        assertNull(WeeklyBodyDelta.compute(emptyList(), listOf(Reading(monday - 1_000, 90f))))
    }

    @Test
    fun readingsWithTheSameTimestampHaveNoDirectionAndNoDelta() {
        val week = listOf(Reading(monday + 2_000, 89f), Reading(monday + 2_000, 90f))

        assertNull(WeeklyBodyDelta.compute(week, listOf(Reading(monday - 1_000, 91f))))
    }

    @Test
    fun tiedTimestampsInTheMiddleDoNotAffectFirstAndLast() {
        val week = listOf(
            Reading(monday + 9_000, 88f),
            Reading(monday + 4_000, 89.5f),
            Reading(monday + 4_000, 89.4f),
            Reading(monday + 1_000, 90f),
        )

        assertEquals(-2.0f, WeeklyBodyDelta.compute(week, emptyList())!!, 0f)
    }
}
