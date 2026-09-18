package com.myfitai.app.domain.progress

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import java.util.concurrent.TimeUnit

/**
 * Schedules one future execution at the exact cadence anchor (last successful analysis + interval).
 * Manual successful runs reset the cadence by replacing the pending work.
 */
class ProgressAnalysisScheduler(
    context: Context,
    private val preferences: ProgressAnalysisPreferences,
    private val aiJobScheduler: AiJobScheduler? = null,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun reschedule(profileId: Long, nowEpochMillis: Long = System.currentTimeMillis()) {
        cancel(profileId)
        enqueue(profileId, nowEpochMillis)
    }

    /** Called by a completed worker; current work is not cancelled while the next one is enqueued. */
    fun scheduleNextAfterAutomaticSuccess(profileId: Long, nowEpochMillis: Long = System.currentTimeMillis()) {
        enqueue(profileId, nowEpochMillis)
    }

    fun cancel(profileId: Long) {
        workManager.cancelAllWorkByTag(tag(profileId))
    }

    private fun enqueue(profileId: Long, nowEpochMillis: Long) {
        val due = preferences.nextDueEpochMillis(profileId) ?: return
        val delay = (due - nowEpochMillis).coerceAtLeast(0L)
        if (aiJobScheduler != null) {
            // The common worker currently has no delayed enqueue API; cadence remains owned here.
            aiJobScheduler.enqueue(AiJobType.PROGRESS_ANALYSIS, profileId, due.toString(), delay)
            return
        }
        val request = OneTimeWorkRequestBuilder<ProgressAnalysisWorker>()
            .setInputData(workDataOf(ProgressAnalysisWorker.KEY_PROFILE_ID to profileId))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.HOURS)
            .addTag(tag(profileId))
            .build()
        workManager.enqueueUniqueWork(uniqueName(profileId, due), ExistingWorkPolicy.KEEP, request)
    }

    private fun tag(profileId: Long) = "progress_analysis_profile_$profileId"
    private fun uniqueName(profileId: Long, dueEpochMillis: Long) = "progress_analysis_${profileId}_$dueEpochMillis"
}
