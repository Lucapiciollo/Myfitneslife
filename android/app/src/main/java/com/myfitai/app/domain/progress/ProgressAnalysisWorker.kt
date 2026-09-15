package com.myfitai.app.domain.progress

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myfitai.app.data.AppDataContainer

class ProgressAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profileId = inputData.getLong(KEY_PROFILE_ID, -1L)
        if (profileId <= 0L) return Result.failure()
        val data = AppDataContainer.get(applicationContext)
        return runCatching {
            data.progressAnalysisService.analyze(profileId)
            data.progressAnalysisScheduler.scheduleNextAfterAutomaticSuccess(profileId)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                when (error) {
                    is ProgressAnalysisService.AnalysisException.NeedsInput -> Result.retry()
                    else -> Result.retry()
                }
            },
        )
    }

    companion object {
        const val KEY_PROFILE_ID = "profile_id"
    }
}
