package com.myfitai.app.domain.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Schedules every AI operation as unique background work, so the same request is never duplicated
 * and the UI can observe it after being recreated.
 */
class AiJobScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val consumedResults = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enqueue(type: AiJobType, profileId: Long, jobKey: String, params: Data = Data.EMPTY) {
        val input = Data.Builder()
            .putAll(params)
            .putString(AiJobWorker.KEY_TYPE, type.name)
            .putLong(AiJobWorker.KEY_PROFILE_ID, profileId)
            .putString(AiJobWorker.KEY_JOB_KEY, jobKey)
            .build()
        val request = OneTimeWorkRequestBuilder<AiJobWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(type.name)
            .build()
        workManager.enqueueUniqueWork(uniqueName(type, profileId, jobKey), ExistingWorkPolicy.KEEP, request)
    }

    fun observe(type: AiJobType, profileId: Long, jobKey: String): Flow<AiJobState> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(type, profileId, jobKey)).map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull() ?: return@map AiJobState.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> AiJobState.Running
                WorkInfo.State.SUCCEEDED -> if (isConsumed(info.id)) AiJobState.Idle else AiJobState.Succeeded(
                    id = info.id,
                    provider = info.outputData.getString(AiJobWorker.KEY_PROVIDER).orEmpty(),
                )
                WorkInfo.State.FAILED -> if (isConsumed(info.id)) AiJobState.Idle else AiJobState.Failed(
                    id = info.id,
                    message = info.outputData.getString(AiJobWorker.KEY_ERROR) ?: "Operazione IA non riuscita",
                )
                WorkInfo.State.CANCELLED -> AiJobState.Idle
            }
        }

    fun cancel(type: AiJobType, profileId: Long, jobKey: String) {
        workManager.cancelUniqueWork(uniqueName(type, profileId, jobKey))
    }

    /**
     * Marks a terminal result as already surfaced, so reopening a screen does not replay an old
     * success or failure banner.
     */
    fun consume(resultId: UUID) {
        val current = consumedResults.getStringSet(KEY_CONSUMED_IDS, emptySet()).orEmpty().toMutableSet()
        if (!current.add(resultId.toString())) return
        val trimmed = if (current.size > MAX_CONSUMED_IDS) current.toList().takeLast(MAX_CONSUMED_IDS).toSet() else current
        consumedResults.edit().putStringSet(KEY_CONSUMED_IDS, trimmed).apply()
    }

    private fun isConsumed(id: UUID): Boolean =
        consumedResults.getStringSet(KEY_CONSUMED_IDS, emptySet()).orEmpty().contains(id.toString())

    private fun uniqueName(type: AiJobType, profileId: Long, jobKey: String): String =
        "ai-job-${type.name}-$profileId-$jobKey"

    private companion object {
        const val PREFS_NAME = "ai_job_results"
        const val KEY_CONSUMED_IDS = "consumed_result_ids"
        const val MAX_CONSUMED_IDS = 40
    }
}

sealed class AiJobState {
    data object Idle : AiJobState()
    data object Running : AiJobState()
    data class Succeeded(val id: UUID, val provider: String) : AiJobState()
    data class Failed(val id: UUID, val message: String) : AiJobState()
}
