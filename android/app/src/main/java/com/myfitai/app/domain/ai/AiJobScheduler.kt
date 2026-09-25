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

    fun enqueue(
        type: AiJobType,
        profileId: Long,
        jobKey: String,
        initialDelayMillis: Long = 0L,
        params: Data = Data.EMPTY,
        replaceExisting: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<AiJobWorker>()
            .setInputData(Data.Builder().putAll(params).putString(AiJobWorker.KEY_TYPE, type.name).putLong(AiJobWorker.KEY_PROFILE_ID, profileId).putString(AiJobWorker.KEY_JOB_KEY, jobKey).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .apply {
                addTag(tag(type, profileId))
                addTag(jobTag(type, profileId, jobKey))
                if (type == AiJobType.PROGRESS_ANALYSIS && jobKey.startsWith(MANUAL_JOB_KEY_PREFIX)) {
                    addTag(manualTag(type, profileId))
                }
            }
            .build()
        val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(name(type, profileId, jobKey), policy, request)
    }

    fun observe(type: AiJobType, profileId: Long, jobKey: String): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(name(type, profileId, jobKey)).map { it.lastOrNull() }

    fun observeActive(type: AiJobType, profileId: Long): Flow<WorkInfo?> =
        workManager.getWorkInfosByTagFlow(tag(type, profileId)).map { workInfos ->
            workInfos
                .filter { info ->
                    when (info.state) {
                        WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> true
                        // The next automatic run can be delayed for weeks; an enqueued manual job is the active user run.
                        WorkInfo.State.ENQUEUED -> info.tags.contains(manualTag(type, profileId)) ||
                            (type == AiJobType.PROGRESS_ANALYSIS && (
                                info.runAttemptCount > 0 ||
                                    dueEpochFromTags(info.tags, type, profileId)?.let { it <= System.currentTimeMillis() } == true
                                ))
                        else -> false
                    }
                }
                .maxByOrNull { info ->
                    statePriority(info.state) + if (info.tags.contains(manualTag(type, profileId))) 1 else 0
                }
        }

    private fun statePriority(state: WorkInfo.State): Int = when (state) {
        WorkInfo.State.RUNNING -> 3
        WorkInfo.State.ENQUEUED -> 2
        WorkInfo.State.BLOCKED -> 1
        else -> 0
    }

    fun cancel(type: AiJobType, profileId: Long, jobKey: String) {
        workManager.cancelUniqueWork(name(type, profileId, jobKey))
    }

    fun cancelAll(type: AiJobType, profileId: Long) {
        workManager.cancelAllWorkByTag(tag(type, profileId))
    }

    private fun tag(type: AiJobType, profileId: Long) = "ai-${type.name.lowercase()}-profile-$profileId"
    private fun manualTag(type: AiJobType, profileId: Long) = "${tag(type, profileId)}-manual"
    private fun jobTag(type: AiJobType, profileId: Long, jobKey: String) = "${tag(type, profileId)}-job-$jobKey"

    private fun dueEpochFromTags(tags: Set<String>, type: AiJobType, profileId: Long): Long? =
        tags.asSequence()
            .firstOrNull { it.startsWith("${tag(type, profileId)}-job-") }
            ?.substringAfterLast("-job-")
            ?.toLongOrNull()

    private fun name(type: AiJobType, profileId: Long, jobKey: String) = "ai-${type.name.lowercase()}-$profileId-$jobKey"

    companion object {
        const val MANUAL_JOB_KEY_PREFIX = "manual-"
    }
}
