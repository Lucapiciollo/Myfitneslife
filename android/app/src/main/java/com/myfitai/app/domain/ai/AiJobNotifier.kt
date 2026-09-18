package com.myfitai.app.domain.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myfitai.app.R
import com.myfitai.app.ui.NutritionPathActivity
import com.myfitai.app.ui.PhysicalEvolutionActivity
import com.myfitai.app.ui.TabHostActivity
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.FoodPlanActivity
import com.myfitai.app.ui.WeeklyReviewActivity
import com.myfitai.app.ui.NutritionAdviceActivity
import com.myfitai.app.ui.MealAlternativeActivity
import com.myfitai.app.ui.CheatEntryActivity

object AiJobNotifier {
    fun notifySuccess(context: Context, type: AiJobType, profileId: Long, jobKey: String, provider: String?) {
        val text = type.successText + provider?.let { " Provider: $it" }.orEmpty()
        notify(context, type, profileId, jobKey, type.successTitle, text)
    }

    fun notifyFailure(context: Context, type: AiJobType, profileId: Long, jobKey: String, message: String) {
        notify(context, type, profileId, jobKey, type.failureTitle, message)
    }

    private fun notify(context: Context, type: AiJobType, profileId: Long, jobKey: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(type.channelId, type.channelName, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = when (type) {
            AiJobType.NUTRITION_PATH -> Intent(context, NutritionPathActivity::class.java).putExtra(NutritionPathActivity.EXTRA_JOB_KEY, jobKey)
            AiJobType.PROGRESS_ANALYSIS -> Intent(context, PhysicalEvolutionActivity::class.java)
            AiJobType.WEEKLY_PLAN -> Intent(context, FoodPlanActivity::class.java).apply {
                jobKey.toLongOrNull()?.let { putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, it) }
            }
            AiJobType.WEEKLY_REVIEW -> Intent(context, WeeklyReviewActivity::class.java)
            AiJobType.NUTRITION_ADVICE -> Intent(context, NutritionAdviceActivity::class.java).putExtra(NutritionAdviceActivity.EXTRA_JOB_KEY, jobKey)
            AiJobType.MEAL_ALTERNATIVES -> Intent(context, MealAlternativeActivity::class.java).apply {
                val parts = jobKey.split('-')
                putExtra(MealAlternativeActivity.EXTRA_WEEK_START_EPOCH_DAY, parts.getOrNull(0)?.toLongOrNull() ?: Long.MIN_VALUE)
                putExtra(MealAlternativeActivity.EXTRA_DAY_EPOCH_DAY, parts.getOrNull(1)?.toLongOrNull() ?: Long.MIN_VALUE)
                putExtra(MealAlternativeActivity.EXTRA_MEAL_ID, parts.getOrNull(2)?.toLongOrNull() ?: -1L)
                putExtra(MealAlternativeActivity.EXTRA_AI_JOB_KEY, jobKey)
            }
            AiJobType.CHEAT_UNDERSTANDING -> Intent(context, CheatEntryActivity::class.java)
        }
        val pending = PendingIntent.getActivity(context, notificationId(profileId, jobKey), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(notificationId(profileId, jobKey), NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.ic_notification_small).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true).build())
    }

    private fun notificationId(profileId: Long, jobKey: String) = "${profileId}:$jobKey".hashCode() and 0x7fffffff
}
