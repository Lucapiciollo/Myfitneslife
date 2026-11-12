package com.myfitai.app.domain.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myfitai.app.data.AppDataContainer
import kotlinx.coroutines.CancellationException

class AiJobWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val type = AiJobType.fromName(inputData.getString(KEY_TYPE)) ?: return Result.failure(workDataOf(KEY_ERROR to "Operazione IA non riconosciuta"))
        val profileId = inputData.getLong(KEY_PROFILE_ID, -1L)
        val jobKey = inputData.getString(KEY_JOB_KEY).orEmpty()
        val handler = AppDataContainer.get(applicationContext).aiJobRegistry.handlerFor(type)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Handler IA non disponibile"))
        val imagePath = inputData.getString(KEY_IMAGE_PATH)
        val outcome = try {
            handler.execute(profileId, jobKey, inputData)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mapFailure(error)
        }
        // Keep the temporary image only while another attempt will actually run.
        val willRetry = outcome is AiJobOutcome.Retry && runAttemptCount < MAX_RETRY_ATTEMPTS
        if (!willRetry) AppDataContainer.get(applicationContext).aiImageJobStore.delete(imagePath)
        return when (outcome) {
            is AiJobOutcome.Success -> {
                AiJobNotifier.notifySuccess(applicationContext, type, profileId, jobKey, outcome.output.getString(KEY_PROVIDER))
                Result.success(outcome.output)
            }
            is AiJobOutcome.Retry -> if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else failure(type, profileId, jobKey, outcome.message)
            is AiJobOutcome.Failure -> failure(type, profileId, jobKey, outcome.message)
        }
    }

    private fun mapFailure(error: Throwable): AiJobOutcome = AiJobFailures.toOutcome(error)

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
        const val MAX_RETRY_ATTEMPTS = 2
        private const val MAX_ERROR_CHARS = 500
    }
}
