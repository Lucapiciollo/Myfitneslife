package com.myfitai.app.domain.ai

import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import kotlinx.coroutines.CancellationException

/**
 * Single place that decides whether an AI job error is worth a WorkManager retry.
 * Transient: no network, non-quota rate limit (429), provider 5xx. Everything else
 * (quota, auth, truncated output, invalid schema, business rejection) is a permanent failure.
 */
object AiJobFailures {
    fun isTransient(error: Throwable): Boolean = when (error) {
        is AiTransportException.Network -> true
        is AiTransportException.Http -> when (error.failureKind) {
            AiTransportFailureKind.RATE_LIMITED, AiTransportFailureKind.PROVIDER_UNAVAILABLE -> true
            AiTransportFailureKind.QUOTA_EXHAUSTED,
            AiTransportFailureKind.INVALID_API_KEY,
            AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD,
            AiTransportFailureKind.MODEL_UNAVAILABLE,
            AiTransportFailureKind.MODEL_REQUIRES_INTERACTIONS,
            AiTransportFailureKind.SCHEMA,
            -> false
            else -> error.statusCode in 500..599
        }
        else -> false
    }

    /** Never log or surface model text: messages come only from our own exception types. */
    fun toOutcome(error: Throwable): AiJobOutcome = when {
        isTransient(error) -> AiJobOutcome.Retry(
            if (error is AiTransportException.Network) "Provider non raggiungibile" else "Provider temporaneamente non disponibile",
        )
        error is AiTransportException.NotConfigured -> AiJobOutcome.Failure("Provider IA non configurato")
        error is AiTransportException.Http -> AiJobOutcome.Failure(error.message ?: "Errore provider")
        else -> AiJobOutcome.Failure(error.message ?: "Operazione IA non riuscita")
    }

    /**
     * Call first inside a handler's `catch (error: Exception)`: cancellation and transient errors
     * must reach [AiJobWorker] so WorkManager can cancel or retry; the rest becomes a Failure.
     */
    fun rethrowIfRetryableOrCancelled(error: Throwable) {
        if (error is CancellationException || isTransient(error)) throw error
    }
}
