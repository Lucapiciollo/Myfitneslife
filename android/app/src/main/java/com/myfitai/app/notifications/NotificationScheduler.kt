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
    suspend fun refresh(nowEpochMillis: Long = time.nowEpochMillis()) {
        cancelAllTracked()
        val newCodes = mutableSetOf<Int>()
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            settings.replaceScheduledRequestCodes(emptySet())
            return
        }
        if (settings.mealRemindersEnabled) scheduleMealReminders(profileId, nowEpochMillis, newCodes)
        if (settings.weeklyReviewEnabled) scheduleWeeklyReview(nowEpochMillis, newCodes)
        settings.replaceScheduledRequestCodes(newCodes)
    }

    fun snoozeMeal(mealId: Long, mealType: String, mealTitle: String, profileId: Long, delayMinutes: Int = 10) {
        val requestCode = stableCode("snooze:$profileId:$mealId:${time.nowEpochMillis() / 60_000}")
        alarmGateway.scheduleSnooze(SnoozeReminderSpec(requestCode, time.nowEpochMillis() + delayMinutes.coerceIn(1, 120) * 60_000L, mealId, mealType, mealTitle, profileId))
    }

    private suspend fun scheduleMealReminders(profileId: Long, now: Long, tracked: MutableSet<Int>) {
        val zone = time.zoneId
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val horizon = today.plusDays(60)
        val leadMillis = settings.mealLeadMinutes * 60_000L
        plans.plans(profileId).first().forEach { row ->
            val weekDate = LocalDate.ofEpochDay(row.weekStartEpochDay)
            if (weekDate.isAfter(horizon) || weekDate.plusDays(6).isBefore(today)) return@forEach
            val snapshot = plans.loadLatestSnapshot(profileId, row.weekStartEpochDay) ?: return@forEach
            snapshot.version.days.forEach dayLoop@{ day ->
                val date = LocalDate.ofEpochDay(day.dateEpochDay)
                if (date.isAfter(horizon)) return@dayLoop
                day.meals.forEach mealLoop@{ meal ->
                    val minutes = meal.timeMinutes ?: return@mealLoop
                    if (minutes !in 0..1439) return@mealLoop
                    val trigger = date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli() - leadMillis
                    if (trigger <= now) return@mealLoop
                    val requestCode = stableCode("meal:$profileId:${snapshot.version.id}:${meal.id}")
                    alarmGateway.scheduleMeal(MealReminderSpec(requestCode, trigger, meal.id, meal.type, meal.title, profileId))
                    tracked += requestCode
                }
            }
        }
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
}
