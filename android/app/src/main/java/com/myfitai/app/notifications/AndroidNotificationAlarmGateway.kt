package com.myfitai.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

class AndroidNotificationAlarmGateway(context: Context) : NotificationAlarmGateway {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun scheduleMeal(spec: MealReminderSpec) {
        val pending = mealIntent(spec.requestCode, spec.mealId, spec.mealType, spec.mealTitle, spec.profileId)
        scheduleWithBestPrecision(spec.triggerAtEpochMillis, pending)
    }

    override fun scheduleWeeklyReview(spec: WeeklyReviewReminderSpec) {
        val pending = PendingIntent.getBroadcast(appContext, spec.requestCode, Intent(appContext, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_WEEKLY_REVIEW }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, spec.triggerAtEpochMillis, AlarmManager.INTERVAL_DAY * 7, pending)
    }

    override fun scheduleSnooze(spec: SnoozeReminderSpec) {
        val pending = mealIntent(spec.requestCode, spec.mealId, spec.mealType, spec.mealTitle, spec.profileId)
        scheduleWithBestPrecision(spec.triggerAtEpochMillis, pending)
    }

    private fun scheduleWithBestPrecision(triggerAtEpochMillis: Long, pending: PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
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
        listOf(ReminderReceiver.ACTION_MEAL, ReminderReceiver.ACTION_WEEKLY_REVIEW).forEach { action ->
            val pending = PendingIntent.getBroadcast(appContext, requestCode, Intent(appContext, ReminderReceiver::class.java).apply { this.action = action }, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarmManager.cancel(pending); pending.cancel() }
        }
    }

    private fun mealIntent(requestCode: Int, mealId: Long, mealType: String, mealTitle: String, profileId: Long): PendingIntent = PendingIntent.getBroadcast(appContext, requestCode, Intent(appContext, ReminderReceiver::class.java).apply {
        action = ReminderReceiver.ACTION_MEAL
        putExtra(ReminderReceiver.EXTRA_MEAL_ID, mealId)
        putExtra(ReminderReceiver.EXTRA_MEAL_TYPE, mealType)
        putExtra(ReminderReceiver.EXTRA_MEAL_TITLE, mealTitle)
        putExtra(ReminderReceiver.EXTRA_PROFILE_ID, profileId)
    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
