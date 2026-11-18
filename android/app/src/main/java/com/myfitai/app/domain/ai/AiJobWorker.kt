package com.myfitai.app.domain.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myfitai.app.data.AppDataContainer

class AiJobWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val type = AiJobType.fromName(inputData.getString(KEY_TYPE)) ?: return Result.failure(workDataOf(KEY_ERROR to "Operazione IA non riconosciuta"))
        val profileId = inputData.getLong(KEY_PROFILE_ID, -1L)
        val jobKey = inputData.getString(KEY_JOB_KEY).orEmpty()
        val appData = AppDataContainer.get(applicationContext)
        val handler = appData.aiJobHandler(type)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Handler IA non disponibile"))
        val imagePath = inputData.getString(KEY_IMAGE_PATH)
        val decision = AiJobRunner.run(
            handler = handler,
            profileId = profileId,
            jobKey = jobKey,
            input = inputData,
            runAttemptCount = runAttemptCount,
            imagePath = imagePath,
            deleteImage = appData.aiImageJobStore::delete,
        )
        return when (decision) {
            is AiJobDecision.Success -> {
                // A skipped run (nothing new to analyse) did not produce anything: no "ready" notification.
                if (!decision.output.getBoolean(KEY_SKIPPED, false)) {
                    AiJobNotifier.notifySuccess(applicationContext, type, profileId, jobKey, decision.output.getString(KEY_PROVIDER))
                }
                Result.success(decision.output)
            }
            is AiJobDecision.Retry -> Result.retry()
            is AiJobDecision.Failure -> failure(type, profileId, jobKey, decision.message)
        }
    }

    private fun failure(type: AiJobType, profileId: Long, jobKey: String, message: String): Result {
        // WorkManager Data is capped at 10 KB: an oversized message would throw from the worker itself.
        val safeMessage = message.take(MAX_ERROR_CHARS)
        AiJobNotifier.notifyFailure(applicationContext, type, profileId, jobKey, safeMessage)
        return Result.failure(workDataOf(KEY_ERROR to safeMessage))
    }

    companion object {
        const val KEY_TYPE = "ai_job_type"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_JOB_KEY = "job_key"
        const val KEY_ERROR = "error"
        const val KEY_PROVIDER = "provider"
        const val KEY_IMAGE_PATH = "image_path"
        const val KEY_AUTOMATIC = "automatic"
        const val KEY_SKIPPED = "skipped"
        const val MAX_RETRY_ATTEMPTS = 2
        private const val MAX_ERROR_CHARS = 500
    }
}
