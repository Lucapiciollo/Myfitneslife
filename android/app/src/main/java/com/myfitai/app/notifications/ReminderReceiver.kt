package com.myfitai.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myfitai.app.R
import com.myfitai.app.ui.FoodPlanActivity
import com.myfitai.app.ui.WeeklyReviewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ensureChannels(context)
        when (intent.action) {
            ACTION_MENU_PREVIEW -> {
                showMenuPreview(context, intent)
                rescheduleNextMenuPreview(context)
            }
            ACTION_SNOOZE -> snooze(context, intent)
            ACTION_WEEKLY_REVIEW -> showWeeklyReview(context)
        }
    }

    /** The nightly alarm is one-shot; after firing, calculate and schedule the next planned menu. */
    private fun rescheduleNextMenuPreview(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                com.myfitai.app.data.AppDataContainer.get(context).notificationScheduler.refresh()
            } finally {
                pending.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showMenuPreview(context: Context, source: Intent) {
        if (!canNotify(context)) return
        val menuDate = source.getLongExtra(EXTRA_MENU_DATE_EPOCH_DAY, Long.MIN_VALUE)
        if (menuDate == Long.MIN_VALUE) return
        val meals = source.getStringArrayExtra(EXTRA_MENU_MEALS).orEmpty().filter(String::isNotBlank)
        val dateLabel = java.time.LocalDate.ofEpochDay(menuDate).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ITALIAN))
            .replaceFirstChar { it.titlecase(java.util.Locale.ITALIAN) }
        val preview = meals.take(MAX_PREVIEW_MEALS).joinToString("\n") { "• $it" }
            .ifBlank { context.getString(R.string.notifications_menu_preview_empty) }
        val open = PendingIntent.getActivity(
            context,
            stableCode("open-menu:${source.getLongExtra(EXTRA_PROFILE_ID, -1L)}:$menuDate"),
            Intent(context, com.myfitai.app.ui.TabHostActivity::class.java)
                .putExtra(com.myfitai.app.navigation.BottomNavBinder.EXTRA_SELECTED_TAB, com.myfitai.app.navigation.BottomNavBinder.Tab.FOOD.name)
                .putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, menuDate)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MEALS)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(context.getString(R.string.notifications_menu_preview_title, dateLabel))
            .setContentText(meals.firstOrNull() ?: context.getString(R.string.notifications_menu_preview_empty))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, context.getString(R.string.notifications_menu_preview_open), open)
            .build()
        NotificationManagerCompat.from(context).notify(stableCode("menu-preview:${source.getLongExtra(EXTRA_PROFILE_ID, -1L)}:$menuDate"), notification)
    }

    private fun snooze(context: Context, source: Intent) {
        val mealId = source.getLongExtra(EXTRA_MEAL_ID, -1L)
        if (mealId <= 0L) return
        com.myfitai.app.data.AppDataContainer.get(context).notificationScheduler.snoozeMeal(
            mealId = mealId,
            mealType = source.getStringExtra(EXTRA_MEAL_TYPE).orEmpty(),
            mealTitle = source.getStringExtra(EXTRA_MEAL_TITLE).orEmpty(),
            profileId = source.getLongExtra(EXTRA_PROFILE_ID, -1L),
            delayMinutes = 10,
        )
        NotificationManagerCompat.from(context).cancel(stableCode("meal-notification:$mealId"))
    }

    @SuppressLint("MissingPermission") // canNotify checks POST_NOTIFICATIONS immediately before notification construction.
    private fun showWeeklyReview(context: Context) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context,
            stableCode("weekly-review-open"),
            Intent(context, WeeklyReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REVIEW)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle("Review settimanale")
            .setContentText("La settimana è conclusa: puoi generare la review dai dati reali registrati.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(stableCode("weekly-review-notification"), notification)
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MEALS, "Promemoria pasti", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_REVIEW, "Review settimanale", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        /** Legacy action identity retained only so the scheduler can cancel alarms created by older app versions. */
        const val ACTION_LEGACY_MEAL = "com.myfitai.app.action.MEAL_REMINDER"
        const val ACTION_MENU_PREVIEW = "com.myfitai.app.action.MENU_PREVIEW_REMINDER"
        const val ACTION_SNOOZE = "com.myfitai.app.action.SNOOZE_MEAL"
        const val ACTION_WEEKLY_REVIEW = "com.myfitai.app.action.WEEKLY_REVIEW"
        const val EXTRA_MEAL_ID = "meal_id"
        const val EXTRA_MEAL_TYPE = "meal_type"
        const val EXTRA_MEAL_TITLE = "meal_title"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_MENU_DATE_EPOCH_DAY = "menu_date_epoch_day"
        const val EXTRA_MENU_MEALS = "menu_meals"
        const val CHANNEL_MEALS = "meal_reminders"
        const val CHANNEL_REVIEW = "weekly_review"
        private const val MAX_PREVIEW_MEALS = 6

        fun stableCode(value: String): Int = value.hashCode() and 0x7fffffff
    }
}
