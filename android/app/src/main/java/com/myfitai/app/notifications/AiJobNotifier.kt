package com.myfitai.app.notifications

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
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.FoodPlanActivity
import com.myfitai.app.ui.BodyMeasuresActivity
import com.myfitai.app.ui.BiaActivity
import com.myfitai.app.ui.CheatEntryActivity
import com.myfitai.app.ui.NutritionAdviceActivity
import com.myfitai.app.ui.NutritionPathActivity
import com.myfitai.app.ui.MealAlternativeActivity
import com.myfitai.app.ui.TabHostActivity

/**
 * Notifies the outcome of a background AI job. Each job type owns its channel so the user can
 * silence one kind of result without losing the others.
 */
object AiJobNotifier {
    fun ensureChannel(context: Context, type: AiJobType) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(type.channelId, type.channelName, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun notifySuccess(context: Context, type: AiJobType, profileId: Long, jobKey: String, provider: String?) {
        val detail = provider?.takeIf { it.isNotBlank() }?.let { " Provider: $it" }.orEmpty()
        notify(context, type, profileId, jobKey, type.notificationTitle, type.successText + detail)
    }

    fun notifyFailure(context: Context, type: AiJobType, profileId: Long, jobKey: String, message: String) {
        notify(context, type, profileId, jobKey, type.failureTitle, message)
    }

    private fun notify(context: Context, type: AiJobType, profileId: Long, jobKey: String, title: String, text: String) {
        if (!canNotify(context)) return
        ensureChannel(context, type)
        val notification = NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(context, type, profileId, jobKey))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(type, profileId, jobKey), notification)
    }

    private fun openIntent(context: Context, type: AiJobType, profileId: Long, jobKey: String): PendingIntent {
        val intent = Intent(context, TabHostActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            when (type) {
                AiJobType.WEEKLY_PLAN -> {
                    putExtra(BottomNavBinder.EXTRA_INITIAL_TAB, BottomNavBinder.Tab.FOOD.name)
                    jobKey.toLongOrNull()?.let { putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, it) }
                }
                AiJobType.MEAL_ALTERNATIVES -> {
                    setClass(context, MealAlternativeActivity::class.java)
                    putExtra(MealAlternativeActivity.EXTRA_AI_JOB_KEY, jobKey)
                    jobKey.split('-').takeIf { it.size == 3 }?.let { parts ->
                        putExtra(MealAlternativeActivity.EXTRA_WEEK_START_EPOCH_DAY, parts[0].toLongOrNull() ?: Long.MIN_VALUE)
                        putExtra(MealAlternativeActivity.EXTRA_DAY_EPOCH_DAY, parts[1].toLongOrNull() ?: Long.MIN_VALUE)
                        putExtra(MealAlternativeActivity.EXTRA_MEAL_ID, parts[2].toLongOrNull() ?: -1L)
                    }
                }
                AiJobType.CHEAT_UNDERSTANDING, AiJobType.CHEAT_ADJUSTMENT -> {
                    setClass(context, CheatEntryActivity::class.java)
                    putExtra(CheatEntryActivity.EXTRA_AI_JOB_KEY, jobKey.removeSuffix("-confirmed"))
                    putExtra(CheatEntryActivity.EXTRA_AI_JOB_TYPE, type.name)
                }
                AiJobType.PROGRESS_ANALYSIS, AiJobType.WEEKLY_REVIEW ->
                    putExtra(BottomNavBinder.EXTRA_INITIAL_TAB, BottomNavBinder.Tab.PROGRESS.name)
                AiJobType.BIA_IMPORT -> setClass(context, BiaActivity::class.java)
                AiJobType.NUTRITION_PATH -> {
                    setClass(context, NutritionPathActivity::class.java)
                    putExtra(NutritionPathActivity.EXTRA_JOB_KEY, jobKey)
                }
                AiJobType.BODY_PROPORTIONS -> setClass(context, BodyMeasuresActivity::class.java)
                AiJobType.NUTRITION_ADVICE -> {
                    setClass(context, NutritionAdviceActivity::class.java)
                    putExtra(NutritionAdviceActivity.EXTRA_JOB_KEY, jobKey)
                }
            }
        }
        return PendingIntent.getActivity(
            context,
            notificationId(type, profileId, jobKey),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(type: AiJobType, profileId: Long, jobKey: String): Int =
        "ai-job:${type.name}:$profileId:$jobKey".hashCode() and 0x7fffffff

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
