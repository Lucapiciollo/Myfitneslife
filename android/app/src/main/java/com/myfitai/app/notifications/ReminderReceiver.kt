package com.myfitai.app.notifications

import android.Manifest
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
import com.myfitai.app.ui.MealDetailActivity
import com.myfitai.app.ui.WeeklyReviewActivity

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ensureChannels(context)
        when (intent.action) {
            ACTION_MEAL -> showMeal(context, intent)
            ACTION_SNOOZE -> snooze(context, intent)
            ACTION_WEEKLY_REVIEW -> showWeeklyReview(context)
        }
    }

    private fun showMeal(context: Context, source: Intent) {
        if (!canNotify(context)) return
        val mealId = source.getLongExtra(EXTRA_MEAL_ID, -1L)
        if (mealId <= 0L) return
        val type = source.getStringExtra(EXTRA_MEAL_TYPE).orEmpty().ifBlank { "Pasto" }
        val title = source.getStringExtra(EXTRA_MEAL_TITLE).orEmpty().ifBlank { "Apri il piano per i dettagli" }
        val profileId = source.getLongExtra(EXTRA_PROFILE_ID, -1L)

        val openPending = PendingIntent.getActivity(
            context,
            stableCode("open:$mealId"),
            Intent(context, MealDetailActivity::class.java)
                .putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozePending = PendingIntent.getBroadcast(
            context,
            stableCode("snooze-action:$mealId"),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(EXTRA_MEAL_ID, mealId)
                putExtra(EXTRA_MEAL_TYPE, type)
                putExtra(EXTRA_MEAL_TITLE, title)
                putExtra(EXTRA_PROFILE_ID, profileId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val lead = NotificationPreferences(context).mealLeadMinutes
        val prefix = if (lead > 0) "Tra $lead minuti: $type" else "È ora di $type"
        val notification = NotificationCompat.Builder(context, CHANNEL_MEALS)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(prefix)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Apri pasto", openPending)
            .addAction(0, "Posticipa 10 min", snoozePending)
            .build()

        NotificationManagerCompat.from(context).notify(stableCode("meal-notification:$mealId"), notification)
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
        const val ACTION_MEAL = "com.myfitai.app.action.MEAL_REMINDER"
        const val ACTION_SNOOZE = "com.myfitai.app.action.SNOOZE_MEAL"
        const val ACTION_WEEKLY_REVIEW = "com.myfitai.app.action.WEEKLY_REVIEW"
        const val EXTRA_MEAL_ID = "meal_id"
        const val EXTRA_MEAL_TYPE = "meal_type"
        const val EXTRA_MEAL_TITLE = "meal_title"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val CHANNEL_MEALS = "meal_reminders"
        const val CHANNEL_REVIEW = "weekly_review"

        fun stableCode(value: String): Int = value.hashCode() and 0x7fffffff
    }
}
