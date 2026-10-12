package com.myfitai.app.notifications

import android.content.Context
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class NotificationScheduler(
    context: Context,
    private val plans: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
    private val alarmGateway: NotificationAlarmGateway = AndroidNotificationAlarmGateway(context),
    private val settings: NotificationSettings = NotificationPreferences(context),
) {
    suspend fun refresh(nowEpochMillis: Long = time.nowEpochMillis()): Int {
        cancelAllTracked()
        val newCodes = mutableSetOf<Int>()
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            settings.replaceScheduledRequestCodes(emptySet())
            return 0
        }
        cancelLegacyMealAlarms(profileId)
        if (settings.mealRemindersEnabled) scheduleMealReminders(profileId, nowEpochMillis, newCodes)
        if (settings.weeklyReviewEnabled) scheduleWeeklyReview(nowEpochMillis, newCodes)
        settings.replaceScheduledRequestCodes(newCodes)
        return newCodes.size
    }

    fun snoozeMeal(mealId: Long, mealType: String, mealTitle: String, profileId: Long, delayMinutes: Int = 10) {
        val requestCode = stableCode("snooze:$profileId:$mealId:${time.nowEpochMillis() / 60_000}")
        alarmGateway.scheduleSnooze(SnoozeReminderSpec(requestCode, time.nowEpochMillis() + delayMinutes.coerceIn(1, 120) * 60_000L, mealId, mealType, mealTitle, profileId))
    }

    private suspend fun scheduleMealReminders(profileId: Long, now: Long, tracked: MutableSet<Int>) {
        val zone = time.zoneId
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        cancelStaleMenuPreviewCodes(profileId, today)
        val plannedDates = plannedMenuDates(profileId, today)
        val schedule = MenuReminderTiming.next(now, settings.menuReminderHour, plannedDates, zone) ?: return
        val menuDate = schedule.menuDate
        val meals = menuForDate(profileId, menuDate).orEmpty()
        if (meals.isEmpty()) return
        // One stable PendingIntent per profile: rescheduling must replace, never accumulate previews.
        val requestCode = stableCode("menu-preview:$profileId")
        alarmGateway.scheduleMeal(
            MealReminderSpec(
                requestCode = requestCode,
                triggerAtEpochMillis = schedule.triggerAtEpochMillis,
                menuDateEpochDay = menuDate.toEpochDay(),
                mealTitles = meals.take(MAX_PREVIEW_MEALS).map { "${it.type}: ${it.title}" },
                profileId = profileId,
            ),
        )
        tracked += requestCode
    }

    private suspend fun cancelLegacyMealAlarms(profileId: Long) {
        if (settings.menuPreviewMigrationDone) return
        plans.legacyMealAlarmIds(profileId).forEach { legacy ->
            alarmGateway.cancelLegacyMeal(stableCode("meal:$profileId:${legacy.versionId}:${legacy.mealId}"))
        }
        settings.menuPreviewMigrationDone = true
    }

    /** Clean IDs used by pre-stable-key builds, so upgrades cannot retain more than one menu-preview alarm. */
    private fun cancelStaleMenuPreviewCodes(profileId: Long, from: LocalDate) {
        for (offset in -1..MENU_SEARCH_HORIZON_DAYS) {
            alarmGateway.cancel(stableCode("menu-preview:$profileId:${from.plusDays(offset.toLong())}"))
        }
    }

    private suspend fun plannedMenuDates(profileId: Long, from: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        val firstMenuDate = from.plusDays(1)
        val horizon = from.plusDays(MENU_SEARCH_HORIZON_DAYS.toLong())
        plans.plans(profileId).first().forEach { row ->
            val weekDate = LocalDate.ofEpochDay(row.weekStartEpochDay)
            if (weekDate.isAfter(horizon) || weekDate.plusDays(6).isBefore(firstMenuDate)) return@forEach
            val snapshot = plans.loadLatestSnapshot(profileId, row.weekStartEpochDay) ?: return@forEach
            snapshot.version.days.forEach { day ->
                val date = LocalDate.ofEpochDay(day.dateEpochDay)
                if (!date.isBefore(firstMenuDate) && !date.isAfter(horizon) && day.meals.isNotEmpty()) dates += date
            }
        }
        return dates.distinct().sorted()
    }

    private suspend fun menuForDate(profileId: Long, date: LocalDate): List<com.myfitai.app.domain.food.FoodMeal>? {
        val rows = plans.plans(profileId).first().sortedByDescending { it.weekStartEpochDay }
        for (row in rows) {
            val snapshot = plans.loadLatestSnapshot(profileId, row.weekStartEpochDay) ?: continue
            snapshot.version.days.firstOrNull { it.dateEpochDay == date.toEpochDay() }?.let { day ->
                if (day.meals.isNotEmpty()) return day.meals
            }
        }
        return null
    }

    private fun scheduleWeeklyReview(now: Long, tracked: MutableSet<Int>) {
        val zone = time.zoneId
        val nowDateTime = Instant.ofEpochMilli(now).atZone(zone)
        var monday = nowDateTime.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        var trigger = monday.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        if (trigger <= now) {
            monday = monday.plusWeeks(1)
            trigger = monday.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        }
        val code = stableCode("weekly-review")
        alarmGateway.scheduleWeeklyReview(WeeklyReviewReminderSpec(code, trigger))
        tracked += code
    }

    private fun cancelAllTracked() {
        settings.scheduledRequestCodes().forEach(alarmGateway::cancel)
        settings.replaceScheduledRequestCodes(emptySet())
    }

    private fun stableCode(value: String): Int = value.hashCode() and 0x7fffffff

    private companion object {
        const val MENU_SEARCH_HORIZON_DAYS = 60
        const val MAX_PREVIEW_MEALS = 6
    }
}
