package com.myfitai.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

class AndroidNotificationAlarmGateway(context: Context) : NotificationAlarmGateway {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun scheduleMeal(spec: MealReminderSpec) {
        val pending = menuIntent(spec)
        scheduleWithBestPrecision(spec.triggerAtEpochMillis, pending)
    }

    override fun scheduleWeeklyReview(spec: WeeklyReviewReminderSpec) {
        val pending = PendingIntent.getBroadcast(appContext, spec.requestCode, Intent(appContext, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_WEEKLY_REVIEW }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, spec.triggerAtEpochMillis, AlarmManager.INTERVAL_DAY * 7, pending)
    }

    /** Legacy snooze hook retained for compatibility; the new menu-preview flow doesn't invoke it. */
    override fun scheduleSnooze(spec: SnoozeReminderSpec) {
        val pending = PendingIntent.getBroadcast(
            appContext,
            spec.requestCode,
            Intent(appContext, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_SNOOZE
                putExtra(ReminderReceiver.EXTRA_MEAL_ID, spec.mealId)
                putExtra(ReminderReceiver.EXTRA_MEAL_TYPE, spec.mealType)
                putExtra(ReminderReceiver.EXTRA_MEAL_TITLE, spec.mealTitle)
                putExtra(ReminderReceiver.EXTRA_PROFILE_ID, spec.profileId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleWithBestPrecision(spec.triggerAtEpochMillis, pending)
    }

    private fun scheduleWithBestPrecision(triggerAtEpochMillis: Long, pending: PendingIntent) {
        if (ExactAlarmAccess.isAllowed(appContext)) {
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, pending)
            }.onFailure {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, pending)
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, pending)
        }
    }

    override fun cancel(requestCode: Int) {
        listOf(ReminderReceiver.ACTION_LEGACY_MEAL, ReminderReceiver.ACTION_MENU_PREVIEW, ReminderReceiver.ACTION_WEEKLY_REVIEW).forEach { action ->
            val pending = PendingIntent.getBroadcast(appContext, requestCode, Intent(appContext, ReminderReceiver::class.java).apply { this.action = action }, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarmManager.cancel(pending); pending.cancel() }
        }
    }

    override fun cancelLegacyMeal(requestCode: Int) {
        val pending = PendingIntent.getBroadcast(
            appContext,
            requestCode,
            Intent(appContext, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_LEGACY_MEAL },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    private fun menuIntent(spec: MealReminderSpec): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        spec.requestCode,
        Intent(appContext, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_MENU_PREVIEW
            putExtra(ReminderReceiver.EXTRA_MENU_DATE_EPOCH_DAY, spec.menuDateEpochDay)
            putExtra(ReminderReceiver.EXTRA_MENU_MEALS, spec.mealTitles.toTypedArray())
            putExtra(ReminderReceiver.EXTRA_PROFILE_ID, spec.profileId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
