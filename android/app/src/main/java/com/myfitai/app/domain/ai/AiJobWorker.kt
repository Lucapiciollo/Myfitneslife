package com.myfitai.app.domain.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import com.myfitai.app.data.AppDataContainer

class AiJobWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val type = AiJobType.fromName(inputData.getString(KEY_TYPE)) ?: return Result.failure(workDataOf(KEY_ERROR to "Operazione IA non riconosciuta"))
        val profileId = inputData.getLong(KEY_PROFILE_ID, -1L)
        val jobKey = inputData.getString(KEY_JOB_KEY).orEmpty()
        val handler = AppDataContainer.get(applicationContext).aiJobRegistry.handlerFor(type)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Handler IA non disponibile"))
        val outcome = runCatching { handler.execute(profileId, jobKey, inputData) }.getOrElse { mapFailure(it) }
        return when (outcome) {
            is AiJobOutcome.Success -> {
                AiJobNotifier.notifySuccess(applicationContext, type, profileId, jobKey, outcome.output.getString(KEY_PROVIDER))
                Result.success(outcome.output)
            }
            is AiJobOutcome.Retry -> if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else failure(type, profileId, jobKey, outcome.message)
            is AiJobOutcome.Failure -> failure(type, profileId, jobKey, outcome.message)
        }
    }

    private fun mapFailure(error: Throwable): AiJobOutcome = when (error) {
        is AiTransportException.Network -> AiJobOutcome.Retry("Provider non raggiungibile")
        is AiTransportException.Http -> if (error.failureKind == AiTransportFailureKind.RATE_LIMITED || error.failureKind == AiTransportFailureKind.PROVIDER_UNAVAILABLE) {
            AiJobOutcome.Retry("Provider temporaneamente non disponibile")
        } else {
            AiJobOutcome.Failure(error.message ?: "Errore provider")
        }
        is AiTransportException.NotConfigured -> AiJobOutcome.Failure("Provider IA non configurato")
        else -> AiJobOutcome.Failure(error.message ?: "Operazione IA non riuscita")
    }

    private fun failure(type: AiJobType, profileId: Long, jobKey: String, message: String): Result {
        AiJobNotifier.notifyFailure(applicationContext, type, profileId, jobKey, message)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    companion object {
        const val KEY_TYPE = "ai_job_type"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_JOB_KEY = "job_key"
        const val KEY_ERROR = "error"
        const val KEY_PROVIDER = "provider"
        const val MAX_RETRY_ATTEMPTS = 2
    }
}
