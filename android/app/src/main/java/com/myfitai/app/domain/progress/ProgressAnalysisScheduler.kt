package com.myfitai.app.domain.progress

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Schedules one future execution at the exact cadence anchor (last successful analysis + interval).
 * Manual successful runs reset the cadence by replacing the pending work.
 */
class ProgressAnalysisScheduler(
    context: Context,
    private val preferences: ProgressAnalysisPreferences,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun reschedule(profileId: Long, nowEpochMillis: Long = System.currentTimeMillis()) {
        cancel(profileId)
        val due = preferences.nextDueEpochMillis(profileId) ?: return
        val delay = (due - nowEpochMillis).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ProgressAnalysisWorker>()
            .setInputData(workDataOf(ProgressAnalysisWorker.KEY_PROFILE_ID to profileId))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 6, TimeUnit.HOURS)
            .addTag(tag(profileId))
            .build()
        workManager.enqueueUniqueWork(uniqueName(profileId, due), ExistingWorkPolicy.KEEP, request)
    }

    /** Called by a completed worker; current work is not cancelled while the next one is enqueued. */
    fun scheduleNextAfterAutomaticSuccess(profileId: Long, nowEpochMillis: Long = System.currentTimeMillis()) {
        val due = preferences.nextDueEpochMillis(profileId) ?: return
        val delay = (due - nowEpochMillis).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ProgressAnalysisWorker>()
            .setInputData(workDataOf(ProgressAnalysisWorker.KEY_PROFILE_ID to profileId))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 6, TimeUnit.HOURS)
            .addTag(tag(profileId))
            .build()
        workManager.enqueueUniqueWork(uniqueName(profileId, due), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(profileId: Long) {
        workManager.cancelAllWorkByTag(tag(profileId))
    }

    private fun tag(profileId: Long) = "progress_analysis_profile_$profileId"
    private fun uniqueName(profileId: Long, dueEpochMillis: Long) = "progress_analysis_${profileId}_$dueEpochMillis"
}
