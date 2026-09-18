package com.myfitai.app.domain.food

import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.domain.ai.AiJobScheduler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class NutritionPlanSchedulerTest {

    private val zone = ZoneId.of("Europe/Rome")
    private val scheduler = NutritionPlanScheduler(
        preferences = mock(NutritionPlanSchedulePreferences::class.java),
        aiJobScheduler = mock(AiJobScheduler::class.java),
        zoneId = zone,
    )

    @Test
    fun nextOccurrenceUsesConfiguredDayAndTime() {
        val now = ZonedDateTime.of(2026, 9, 18, 12, 0, 0, 0, zone)

        val due = scheduler.nextOccurrence(
            now = now,
            dayOfWeek = DayOfWeek.SATURDAY,
            timeMinutes = 9 * 60 + 30,
        )

        assertEquals(DayOfWeek.SATURDAY, due.dayOfWeek)
        assertEquals(9, due.hour)
        assertEquals(30, due.minute)
    }

    @Test
    fun nextOccurrenceMovesToFollowingWeekWhenConfiguredTimeAlreadyPassed() {
        val now = ZonedDateTime.of(2026, 9, 19, 12, 0, 0, 0, zone)

        val due = scheduler.nextOccurrence(
            now = now,
            dayOfWeek = DayOfWeek.SATURDAY,
            timeMinutes = 9 * 60,
        )

        assertEquals(26, due.dayOfMonth)
        assertEquals(9, due.hour)
        assertEquals(0, due.minute)
    }
}
