package com.myfitai.app.domain.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Data
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class AiJobScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(type: AiJobType, profileId: Long, jobKey: String, initialDelayMillis: Long = 0L, params: Data = Data.EMPTY) {
        val request = OneTimeWorkRequestBuilder<AiJobWorker>()
            .setInputData(Data.Builder().putAll(params).putString(AiJobWorker.KEY_TYPE, type.name).putLong(AiJobWorker.KEY_PROFILE_ID, profileId).putString(AiJobWorker.KEY_JOB_KEY, jobKey).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(tag(type, profileId))
            .build()
        workManager.enqueueUniqueWork(name(type, profileId, jobKey), ExistingWorkPolicy.KEEP, request)
    }

    fun observe(type: AiJobType, profileId: Long, jobKey: String): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(name(type, profileId, jobKey)).map { it.lastOrNull() }

    fun cancel(type: AiJobType, profileId: Long, jobKey: String) {
        workManager.cancelUniqueWork(name(type, profileId, jobKey))
    }

    fun cancelAll(type: AiJobType, profileId: Long) {
        workManager.cancelAllWorkByTag(tag(type, profileId))
    }

    private fun tag(type: AiJobType, profileId: Long) = "ai-${type.name.lowercase()}-profile-$profileId"

    private fun name(type: AiJobType, profileId: Long, jobKey: String) = "ai-${type.name.lowercase()}-$profileId-$jobKey"
}
