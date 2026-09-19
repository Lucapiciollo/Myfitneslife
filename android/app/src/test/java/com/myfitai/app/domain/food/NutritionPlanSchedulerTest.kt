package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class NutritionPlanSchedulerTest {

    private val zone = ZoneId.of("Europe/Rome")

    @Test
    fun appRefreshPreservesAlreadyDueAutomaticWork() {
        assertTrue(NutritionPlanScheduler.isDueJob("auto-1000", 1000L))
        assertTrue(NutritionPlanScheduler.isDueJob("auto-1000", 1500L))
        assertFalse(NutritionPlanScheduler.isDueJob("auto-2000", 1500L))
        assertFalse(NutritionPlanScheduler.isDueJob("manual-1000", 1500L))
        assertFalse(NutritionPlanScheduler.isDueJob(null, 1500L))
    }

    @Test
    fun nextOccurrenceUsesConfiguredDayAndTime() {
        val now = ZonedDateTime.of(2026, 9, 18, 12, 0, 0, 0, zone)

        val due = NutritionPlanScheduler.nextOccurrence(
            now = now,
            frequency = com.myfitai.app.data.profile.NutritionPlanSchedulePreferences.Frequency.WEEKLY,
            dayOfWeek = DayOfWeek.SATURDAY,
            timeMinutes = 9 * 60 + 30,
            zoneId = zone,
        )

        assertEquals(DayOfWeek.SATURDAY, due.dayOfWeek)
        assertEquals(9, due.hour)
        assertEquals(30, due.minute)
    }

    @Test
    fun nextOccurrenceMovesToFollowingWeekWhenConfiguredTimeAlreadyPassed() {
        val now = ZonedDateTime.of(2026, 9, 19, 12, 0, 0, 0, zone)

        val due = NutritionPlanScheduler.nextOccurrence(
            now = now,
            frequency = com.myfitai.app.data.profile.NutritionPlanSchedulePreferences.Frequency.WEEKLY,
            dayOfWeek = DayOfWeek.SATURDAY,
            timeMinutes = 9 * 60,
            zoneId = zone,
        )

        assertEquals(26, due.dayOfMonth)
        assertEquals(9, due.hour)
        assertEquals(0, due.minute)
    }
}
