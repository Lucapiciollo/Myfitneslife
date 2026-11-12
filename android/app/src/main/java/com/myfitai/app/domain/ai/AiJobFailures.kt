package com.myfitai.app.domain.ai

import com.myfitai.app.ai.AiFailure
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.NutritionAdviceService
import kotlinx.coroutines.CancellationException

/**
 * Adapter between [AiFailure] (the single classification of AI errors) and what a job handler returns.
 * It holds no table of its own: whether an error is transient, its stable code and its user message all come
 * from [AiFailure]. Transient: no network, non-quota rate limit (429), provider 5xx. Everything else (quota,
 * auth, refusal, truncated/empty/malformed output, invalid schema, business rejection) is permanent.
 *
 * Messages that reach WorkManager output, notifications and the UI never contain arbitrary exception
 * text: an exception message may embed model output (parser errors, `BIA_..._<value>`). Only
 * (1) the closed [AiFailure] texts/codes, (2) the closed business codes ([AiFailure.BUSINESS_CODES]) and
 * (3) the typed static texts of the cheat flow are forwarded; anything else becomes the handler's fixed
 * fallback message.
 */
object AiJobFailures {
    /** Closed set of business codes; owned by [AiFailure] (kept here as the name existing callers/tests use). */
    internal val KNOWN_CODES: Set<String> get() = AiFailure.BUSINESS_CODES

    fun isTransient(error: Throwable): Boolean = AiFailure.classify(error).retryable

    fun toOutcome(error: Throwable): AiJobOutcome {
        val failure = AiFailure.classify(error)
        return if (failure.retryable) AiJobOutcome.Retry(failure.userMessage) else failure(error, GENERIC_MESSAGE)
    }

    fun failure(error: Throwable, fallback: String): AiJobOutcome.Failure = AiJobOutcome.Failure(safeMessage(error, fallback))

    /** Message safe to persist/show: never contains text copied from the model output or the provider. */
    fun safeMessage(error: Throwable, fallback: String): String {
        // Typed exceptions of the cheat flow carry static texts and field names chosen by the app.
        if (error is CheatAdjustmentService.AdjustmentException) return error.message ?: fallback
        return when (val failure = AiFailure.classify(error)) {
            is AiFailure.BusinessRuleViolated -> failure.ruleCode ?: fallback
            AiFailure.Unexpected -> error.message?.takeIf { it in STATIC_MESSAGES } ?: fallback
            else -> when (failure.surface) {
                AiFailure.Surface.MESSAGE -> failure.userMessage
                AiFailure.Surface.CODE -> failure.surfaceCode
            }
        }
    }

    /** Stable business code of a message, or null when it is not in the closed set (see [AiFailure.stableBusinessCode]). */
    fun stableCode(message: String?): String? = AiFailure.stableBusinessCode(message)

    /**
     * Call first inside a handler's `catch (error: Exception)`: cancellation and transient errors
     * must reach [AiJobWorker] so WorkManager can cancel or retry; the rest becomes a Failure.
     */
    fun rethrowIfRetryableOrCancelled(error: Throwable) {
        if (error is CancellationException || isTransient(error)) throw error
    }

    private const val GENERIC_MESSAGE = "Operazione IA non riuscita"

    private val STATIC_MESSAGES = setOf(
        NutritionAdviceService.OUT_OF_SCOPE_MESSAGE,
        "Profilo non disponibile",
        "Nessuna rilevazione BIA con valori da analizzare",
    )
}
