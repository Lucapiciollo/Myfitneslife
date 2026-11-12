package com.myfitai.app.domain.ai

import androidx.work.Data
import kotlinx.coroutines.CancellationException

/** What the worker must do after one execution attempt. Pure data: the Android side effects stay in [AiJobWorker]. */
sealed class AiJobDecision {
    data class Success(val output: Data) : AiJobDecision()
    /** Ask WorkManager to run the job again. */
    data class Retry(val message: String) : AiJobDecision()
    data class Failure(val message: String) : AiJobDecision()
}

/**
 * Decision logic of [AiJobWorker], kept free of Android framework types so it is unit-testable on the JVM.
 *
 * - [CancellationException] is always rethrown.
 * - Any other [Throwable] (including [OutOfMemoryError]) is mapped to a decision so the temporary image is
 *   cleaned up and the failure is notified.
 * - The temporary image is kept only while another attempt will really run.
 */
internal object AiJobRunner {
    suspend fun run(
        handler: AiJobHandler,
        profileId: Long,
        jobKey: String,
        input: Data,
        runAttemptCount: Int,
        imagePath: String?,
        deleteImage: (String?) -> Unit,
    ): AiJobDecision {
        val outcome = try {
            handler.execute(profileId, jobKey, input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AiJobFailures.toOutcome(error)
        }
        val decision = when (outcome) {
            is AiJobOutcome.Success -> AiJobDecision.Success(outcome.output)
            is AiJobOutcome.Retry ->
                if (runAttemptCount < AiJobWorker.MAX_RETRY_ATTEMPTS) AiJobDecision.Retry(outcome.message) else AiJobDecision.Failure(outcome.message)
            is AiJobOutcome.Failure -> AiJobDecision.Failure(outcome.message)
        }
        if (decision !is AiJobDecision.Retry) deleteImage(imagePath)
        return decision
    }
}