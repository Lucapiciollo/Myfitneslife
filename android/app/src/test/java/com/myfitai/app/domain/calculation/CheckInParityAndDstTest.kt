package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
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
 * K2 lotto 3: (4) parita allenamento check-in vs programma e (5) giorni di calendario a cavallo del DST.
 * Europa/Roma: fine ora legale domenica 25 ottobre 2026, inizio domenica 29 marzo 2026.
 */
class CheckInParityAndDstTest {

    // ---- (4) allenamento: il check-in e una quota prudente del costo del programma ------------

    @Test
    fun oneHourModerate_profileD78kg_andProfileU89_5kg_programVsCheckIn() {
        val session = TrainingSession(60, Intensity.MODERATE)
        // Programma: MET netti x peso x ore. D = 4,0 x 78 = 312 (il "+312" dell'audit), U = 4,0 x 89,5 = 358.
        assertEquals(312, DayEnergyEngine.exerciseKcal(78.0, session))
        assertEquals(358, DayEnergyEngine.exerciseKcal(89.5, session))
        // Check-in: stessa quota per ogni peso (+140), deliberatamente piu bassa: si somma al TDEE abituale
        // che include gia l'allenamento medio, quindi non e il costo pieno (vedi KDoc).
        assertEquals(140, DailyActivityCheckInEngine.adjustmentKcal(60, Intensity.MODERATE))
        val share78 = 140.0 / DayEnergyEngine.exerciseKcal(78.0, session)
        val share895 = 140.0 / DayEnergyEngine.exerciseKcal(89.5, session)
        assertTrue("quota D $share78", share78 in 0.40..0.50)
        assertTrue("quota U $share895", share895 in 0.35..0.45)
    }

    @Test
    fun checkIn_neverExceedsTheProgramCost_atTheSameDurationAndIntensity() {
        for (minutes in listOf(30, 45, 60, 75)) {
            for (intensity in Intensity.entries) {
                val checkIn = DailyActivityCheckInEngine.adjustmentKcal(minutes, intensity)
                assertTrue(checkIn <= DailyActivityCheckInEngine.MAX_ADJUSTMENT_KCAL)
                for (weight in 40..150) {
                    val program = DayEnergyEngine.exerciseKcal(weight.toDouble(), TrainingSession(minutes, intensity))
                    assertTrue("$minutes min $intensity $weight kg: check-in $checkIn > programma $program", checkIn <= program)
                }
            }
        }
    }

    @Test
    fun checkInTdee_addsOnlyTheCappedQuota_toTheHabitualTdee() {
        assertEquals(
            2067.7 + 140.0,
            DailyActivityCheckInEngine.effectiveTdeeKcal(1334.0, 2067.7, "PLANNED_WORKOUT", 140)!!,
            1e-9,
        )
        // Anche un valore salvato oltre il tetto non lo supera.
        assertEquals(
            2067.7 + DailyActivityCheckInEngine.MAX_ADJUSTMENT_KCAL,
            DailyActivityCheckInEngine.effectiveTdeeKcal(1334.0, 2067.7, "PLANNED_WORKOUT", 999)!!,
            1e-9,
        )
    }

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
