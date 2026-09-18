package com.myfitai.app.domain.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.notifications.AiJobNotifier

/**
 * Runs any AI operation off the UI: the provider round trip survives navigation and process death,
 * the validated result is persisted so the screen can reattach, and the user is notified when it
 * completes.
 */
class AiJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val type = AiJobType.fromNameOrNull(inputData.getString(KEY_TYPE))
            ?: return failure(null, 0L, "", "Tipo di operazione IA non valido")
        val profileId = inputData.getLong(KEY_PROFILE_ID, -1L)
        val jobKey = inputData.getString(KEY_JOB_KEY).orEmpty()
        if (profileId <= 0L) return failure(type, profileId, jobKey, "Richiesta IA non valida")

        val data = AppDataContainer.get(applicationContext)
        if (data.activeProfileStore.currentIdOrNull() != profileId) {
            return failure(type, profileId, jobKey, "Il profilo attivo è cambiato")
        }
        val handler = data.aiJobRegistry.handlerFor(type)
            ?: return failure(type, profileId, jobKey, "Operazione IA non disponibile")

        val imagePath = inputData.getString(KEY_IMAGE_PATH)
        val outcome = runCatching { handler.execute(profileId, jobKey, inputData) }
            .getOrElse { AiJobFailureMapper.map(it) }
        // Keep image input alive across transient retries; delete it only once the job settles.
        if (outcome !is AiJobOutcome.Retry) data.aiImageJobStore.delete(imagePath)

        return when (outcome) {
            is AiJobOutcome.Success -> {
                data.aiJobResultRepository.recordSuccess(profileId, type, jobKey, outcome.payloadJson, outcome.provider)
                AiJobNotifier.notifySuccess(applicationContext, type, profileId, jobKey, outcome.provider)
                if (type == AiJobType.WEEKLY_PLAN) {
                    data.nutritionAutoGenerationScheduler.refresh(profileId)
                }
                Result.success(Data.Builder().putString(KEY_PROVIDER, outcome.provider.orEmpty()).build())
            }
            // Retries stay silent: the user is only told once the job actually settles.
            is AiJobOutcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                failure(type, profileId, jobKey, outcome.reason)
            }
            is AiJobOutcome.Failure -> failure(type, profileId, jobKey, outcome.message)
        }
    }

    private suspend fun failure(type: AiJobType?, profileId: Long, jobKey: String, message: String): Result {
        if (type != null && profileId > 0L) {
            val data = AppDataContainer.get(applicationContext)
            data.aiJobResultRepository.recordFailure(profileId, type, jobKey, message)
            AiJobNotifier.notifyFailure(applicationContext, type, profileId, jobKey, message)
        }
        return Result.failure(Data.Builder().putString(KEY_ERROR, message.take(500)).build())
    }

    companion object {
        const val KEY_TYPE = "ai_job_type"
        const val KEY_PROFILE_ID = "ai_job_profile_id"
        const val KEY_JOB_KEY = "ai_job_key"
        const val KEY_PROVIDER = "ai_job_provider"
        const val KEY_ERROR = "ai_job_error"
        const val KEY_IMAGE_PATH = "ai_job_image_path"
        private const val MAX_ATTEMPTS = 3
    }
}
