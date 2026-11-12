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

    private val day = 86_400_000L

    @Test
    fun singleReadingWithReferenceWithinTheCapKeepsTheDeltaAndDeclaresTheRealSpan() {
        val week = listOf(Reading(monday + 2 * day, 88.5f))
        val previous = listOf(Reading(monday + 2 * day - WeeklyBodyDelta.MAX_REFERENCE_GAP_DAYS * day, 90f))

        val delta = WeeklyBodyDelta.describe(week, previous)!!
        assertEquals(-1.5f, delta.value, 0f)
        assertEquals(WeeklyBodyDelta.MAX_REFERENCE_GAP_DAYS, delta.spanDays)
    }

    @Test
    fun singleReadingWithReferenceBeyondTheCapHasNoWeeklyDelta() {
        val week = listOf(Reading(monday + 2 * day, 88.5f))
        val stale = listOf(Reading(monday + 2 * day - (WeeklyBodyDelta.MAX_REFERENCE_GAP_DAYS + 1) * day, 95f))

        assertNull(WeeklyBodyDelta.describe(week, stale))
        assertNull(WeeklyBodyDelta.compute(week, stale))
    }

    @Test
    fun twoReadingsInTheWeekDeclareTheirOwnSpan() {
        val week = listOf(Reading(monday + 1 * day, 90f), Reading(monday + 4 * day, 89f))

        val delta = WeeklyBodyDelta.describe(week, emptyList())!!
        assertEquals(-1.0f, delta.value, 0f)
        assertEquals(3, delta.spanDays)
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
