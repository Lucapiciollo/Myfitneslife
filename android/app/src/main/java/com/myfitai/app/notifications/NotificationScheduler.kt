package com.myfitai.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class NotificationScheduler(
    context: Context,
    private val plans: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val preferences = NotificationPreferences(appContext)

    suspend fun refresh(nowEpochMillis: Long = time.nowEpochMillis()) {
        cancelAllTracked()
        val newCodes = mutableSetOf<Int>()
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            preferences.replaceScheduledRequestCodes(emptySet())
            return
        }

        if (preferences.mealRemindersEnabled) scheduleMealReminders(profileId, nowEpochMillis, newCodes)
        if (preferences.weeklyReviewEnabled) scheduleWeeklyReview(nowEpochMillis, newCodes)
        preferences.replaceScheduledRequestCodes(newCodes)
    }

    fun snoozeMeal(mealId: Long, mealType: String, mealTitle: String, profileId: Long, delayMinutes: Int = 10) {
        val requestCode = stableCode("snooze:$profileId:$mealId:${time.nowEpochMillis() / 60_000}")
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            time.nowEpochMillis() + delayMinutes.coerceIn(1, 120) * 60_000L,
            mealIntent(requestCode, mealId, mealType, mealTitle, profileId),
        )
    }

    private suspend fun scheduleMealReminders(profileId: Long, now: Long, tracked: MutableSet<Int>) {
        val zone = time.zoneId
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val horizon = today.plusDays(60)
        val leadMillis = preferences.mealLeadMinutes * 60_000L
        val planRows = plans.plans(profileId).first()

        planRows.forEach { row ->
            val weekDate = LocalDate.ofEpochDay(row.weekStartEpochDay)
            if (weekDate.isAfter(horizon) || weekDate.plusDays(6).isBefore(today)) return@forEach
            val snapshot = plans.loadLatestSnapshot(profileId, row.weekStartEpochDay) ?: return@forEach
            snapshot.version.days.forEach dayLoop@{ day ->
                val date = LocalDate.ofEpochDay(day.dateEpochDay)
                if (date.isAfter(horizon)) return@dayLoop
                day.meals.forEach mealLoop@{ meal ->
                    val minutes = meal.timeMinutes ?: return@mealLoop
                    if (minutes !in 0..1439) return@mealLoop
                    val mealTime = date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
                    val trigger = mealTime - leadMillis
                    if (trigger <= now) return@mealLoop
                    val requestCode = stableCode("meal:$profileId:${snapshot.version.id}:${meal.id}")
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        trigger,
                        mealIntent(requestCode, meal.id, meal.type, meal.title, profileId),
                    )
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
        val intent = PendingIntent.getBroadcast(
            appContext,
            code,
            Intent(appContext, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_WEEKLY_REVIEW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            trigger,
            AlarmManager.INTERVAL_DAY * 7,
            intent,
        )
        tracked += code
    }

    private fun mealIntent(requestCode: Int, mealId: Long, mealType: String, mealTitle: String, profileId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            requestCode,
            Intent(appContext, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_MEAL
                putExtra(ReminderReceiver.EXTRA_MEAL_ID, mealId)
                putExtra(ReminderReceiver.EXTRA_MEAL_TYPE, mealType)
                putExtra(ReminderReceiver.EXTRA_MEAL_TITLE, mealTitle)
                putExtra(ReminderReceiver.EXTRA_PROFILE_ID, profileId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelAllTracked() {
        preferences.scheduledRequestCodes().forEach { code ->
            listOf(ReminderReceiver.ACTION_MEAL, ReminderReceiver.ACTION_WEEKLY_REVIEW).forEach { action ->
                val pending = PendingIntent.getBroadcast(
                    appContext,
                    code,
                    Intent(appContext, ReminderReceiver::class.java).apply { this.action = action },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (pending != null) {
                    alarmManager.cancel(pending)
                    pending.cancel()
                }
            }
        }
        preferences.replaceScheduledRequestCodes(emptySet())
    }

    private fun stableCode(value: String): Int = value.hashCode() and 0x7fffffff
}
