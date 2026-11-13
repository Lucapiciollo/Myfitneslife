package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.review.WeeklyBodyDelta
import com.myfitai.app.domain.review.WeeklyBodyDelta.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * K2 lotto 3: giorni di calendario a cavallo del DST (il confronto check-in/programma e stato rimosso con la semplificazione delle attivita).
 * Europa/Roma: fine ora legale domenica 25 ottobre 2026, inizio domenica 29 marzo 2026.
 */
class CheckInParityAndDstTest {

    // ---- (5) DST ------------------------------------------------------------------------------

    private val rome = ZoneId.of("Europe/Rome")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 8): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(rome).toInstant().toEpochMilli()

    @Test
    fun weeklyDelta_referenceFourteenCalendarDaysBack_acrossTheOctoberChange_isKept() {
        // 12 -> 26 ottobre 2026 sono 14 giorni di calendario ma 14 giorni + 1 ora in millisecondi.
        val week = listOf(Reading(at(2026, 10, 26), 88.5f))
        val previous = listOf(Reading(at(2026, 10, 12), 90f))

        val delta = WeeklyBodyDelta.describe(week, previous, rome)
        assertNotNull(delta)
        assertEquals(-1.5f, delta!!.value, 0f)
        assertEquals(14, delta.spanDays)
        // Un giorno in piu e fuori tetto.
        assertNull(WeeklyBodyDelta.describe(week, listOf(Reading(at(2026, 10, 11), 90f)), rome))
    }

    @Test
    fun weeklyDelta_spanAcrossTheMarchChange_isCalendarDays() {
        // 25 marzo 12:00 -> 1 aprile 12:00: 7 giorni di calendario (6 giorni e 23 ore in millisecondi).
        val week = listOf(Reading(at(2026, 3, 25, 12), 90f), Reading(at(2026, 4, 1, 12), 89f))
        assertEquals(7, WeeklyBodyDelta.describe(week, emptyList(), rome)!!.spanDays)
        // Riferimento 14 giorni prima a cavallo del 29 marzo: 16 -> 30 marzo.
        val single = WeeklyBodyDelta.describe(listOf(Reading(at(2026, 3, 30), 88f)), listOf(Reading(at(2026, 3, 16), 90f)), rome)!!
        assertEquals(14, single.spanDays)
    }

    private fun weights(vararg points: Pair<Long, Float>): List<Pair<Long, Float?>> = points.map { it.first to it.second }

    @Test
    fun metricSnapshot_recentWindowIsFiftySixCalendarDays_acrossTheOctoberChange() {
        val anchor = at(2026, 10, 26)
        // 31 agosto = esattamente 56 giorni di calendario prima (56 giorni + 1 ora in millisecondi): dentro.
        val inside = ProfileCalculationService.metricSnapshot(weights(at(2026, 8, 31) to 90f, anchor to 89f), rome, "weightKg")
        assertEquals(56, inside.recentSpanDays)
        assertEquals(2, inside.recentTrend.count)
        // 30 agosto = 57 giorni: fuori dalla finestra.
        val outside = ProfileCalculationService.metricSnapshot(weights(at(2026, 8, 30) to 90f, anchor to 89f), rome, "weightKg")
        assertEquals(1, outside.recentTrend.count)
        assertEquals(0, outside.recentSpanDays)
    }

    @Test
    fun metricSnapshot_spanAcrossTheMarchChange_isNotTruncatedToSixDays() {
        val snapshot = ProfileCalculationService.metricSnapshot(
            weights(at(2026, 3, 25, 12) to 90f, at(2026, 4, 1, 12) to 89f), rome, "weightKg",
        )
        assertEquals(7, snapshot.recentSpanDays)
    }

    @Test
    fun metricSnapshot_passesTheNoiseKey_toTheTrendVerdict() {
        val points = weights(at(2026, 5, 1) to 80.0f, at(2026, 5, 20) to 80.5f)
        // Con la chiave: 0,5 kg e sotto la soglia di rumore del peso (0,8) -> stabile.
        assertEquals(
            LocalCalculationEngine.TrendDirection.STABLE,
            ProfileCalculationService.metricSnapshot(points, rome, "weightKg").recentTrend.direction,
        )
        // Senza chiave resta la soglia storica 0,05 -> variazione.
        assertEquals(
            LocalCalculationEngine.TrendDirection.UP,
            ProfileCalculationService.metricSnapshot(points, rome).recentTrend.direction,
        )
        assertEquals(
            LocalCalculationEngine.TrendDirection.STABLE,
            ProfileCalculationService.trendOf(listOf(1L to 15.0, 2L to 14.4), "bodyFatPercent").direction,
        )
    }
}
