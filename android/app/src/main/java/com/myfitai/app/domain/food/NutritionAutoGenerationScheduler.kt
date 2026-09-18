package com.myfitai.app.domain.food

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.myfitai.app.data.profile.NutritionAutoGenerationPreferences
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.ai.AiJobWorker
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** Schedules the next automatic nutrition-plan request using the same AI worker as manual runs. */
class NutritionAutoGenerationScheduler(
    context: Context,
    private val preferences: NutritionAutoGenerationPreferences,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun refresh(profileId: Long, now: LocalDate = LocalDate.now()) {
        cancel(profileId)
        if (!preferences.enabled(profileId)) return

        val monday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val next = when (preferences.cadence(profileId)) {
            NutritionAutoGenerationPreferences.Cadence.DAY -> now.plusDays(1)
            NutritionAutoGenerationPreferences.Cadence.WEEK -> monday.plusWeeks(1)
            NutritionAutoGenerationPreferences.Cadence.TWO_WEEKS -> monday.plusWeeks(2)
            NutritionAutoGenerationPreferences.Cadence.THREE_WEEKS -> monday.plusWeeks(3)
            NutritionAutoGenerationPreferences.Cadence.MONTH -> now.plusMonths(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }
        enqueue(profileId, next)
    }

    fun cancel(profileId: Long) {
        workManager.cancelUniqueWork(uniqueName(profileId))
    }

    private fun enqueue(profileId: Long, weekStart: LocalDate) {
        val delay = java.time.Duration.between(java.time.LocalDateTime.now(), weekStart.atStartOfDay()).toMillis().coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<com.myfitai.app.domain.ai.AiJobWorker>()
            .setInputData(
                Data.Builder()
                    .putString(AiJobWorker.KEY_TYPE, AiJobType.WEEKLY_PLAN.name)
                    .putLong(AiJobWorker.KEY_PROFILE_ID, profileId)
                    .putString(AiJobWorker.KEY_JOB_KEY, weekStart.toEpochDay().toString())
                    .build()
            )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_PREFIX + profileId)
            .build()
        workManager.enqueueUniqueWork(uniqueName(profileId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun uniqueName(profileId: Long): String = "$TAG_PREFIX$profileId"

    private companion object {
        const val TAG_PREFIX = "nutrition-auto-generation-"
    }
}
