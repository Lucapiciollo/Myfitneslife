package com.myfitai.app.notifications

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Local-time policy for the nightly preview of the next planned menu. */
object MenuReminderTiming {
    data class Schedule(val triggerAtEpochMillis: Long, val menuDate: LocalDate)

    /** Schedules each planned menu on its preceding evening, returning the first alarm still in the future. */
    fun next(
        nowEpochMillis: Long,
        hour: Int,
        plannedMenuDates: Collection<LocalDate>,
        zone: ZoneId,
    ): Schedule? {
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
        val reminderTime = LocalTime.of(hour.coerceIn(0, 23), 0)
        return plannedMenuDates.asSequence()
            .distinct()
            .sorted()
            .mapNotNull { menuDate ->
                val triggerDate = menuDate.minusDays(1)
                val trigger = triggerDate.atTime(reminderTime).atZone(zone).toInstant().toEpochMilli()
                Schedule(trigger, menuDate).takeIf { trigger > nowEpochMillis }
            }
            .firstOrNull()
    }
}
