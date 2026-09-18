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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class AiJobScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(type: AiJobType, profileId: Long, jobKey: String) {
        val request = OneTimeWorkRequestBuilder<AiJobWorker>()
            .setInputData(workDataOf(
                AiJobWorker.KEY_TYPE to type.name,
                AiJobWorker.KEY_PROFILE_ID to profileId,
                AiJobWorker.KEY_JOB_KEY to jobKey,
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(name(type, profileId, jobKey), ExistingWorkPolicy.KEEP, request)
    }

    fun observe(type: AiJobType, profileId: Long, jobKey: String): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(name(type, profileId, jobKey)).map { it.lastOrNull() }

    private fun name(type: AiJobType, profileId: Long, jobKey: String) = "ai-${type.name.lowercase()}-$profileId-$jobKey"
}
