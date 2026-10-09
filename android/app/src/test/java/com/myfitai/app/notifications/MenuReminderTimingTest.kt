package com.myfitai.app.notifications

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MenuReminderTimingTest {
    private val today = LocalDate.of(2026, 10, 8)

    @Test
    fun schedulesAtConfiguredEveningTimeTheNightBeforeMenuDate() {
        val now = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        val result = MenuReminderTiming.next(now, 20, listOf(today.plusDays(1), today.plusDays(2)), ZoneOffset.UTC)!!

        assertEquals(today.atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), result.triggerAtEpochMillis)
        assertEquals(today.plusDays(1), result.menuDate)
    }

    @Test
    fun afterReminderTimeSchedulesTomorrowEveningForNextPlannedMenu() {
        val now = today.atTime(20, 5).toInstant(ZoneOffset.UTC).toEpochMilli()

        val result = MenuReminderTiming.next(now, 20, listOf(today.plusDays(1), today.plusDays(3)), ZoneOffset.UTC)!!

        assertEquals(today.plusDays(2).atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), result.triggerAtEpochMillis)
        assertEquals(today.plusDays(3), result.menuDate)
    }

    @Test
    fun noFuturePlannedMenuDoesNotSchedule() {
        val now = today.atTime(21, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertNull(MenuReminderTiming.next(now, 20, listOf(today.plusDays(1)), ZoneOffset.UTC))
    }

    @Test
    fun skipsPlannedDateWhoseReminderEveningAlreadyPassed() {
        val now = today.atTime(20, 1).toInstant(ZoneOffset.UTC).toEpochMilli()
        val result = MenuReminderTiming.next(now, 20, listOf(today.plusDays(1), today.plusDays(3)), ZoneOffset.UTC)!!
        assertEquals(today.plusDays(2).atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), result.triggerAtEpochMillis)
        assertEquals(today.plusDays(3), result.menuDate)
    }
}
