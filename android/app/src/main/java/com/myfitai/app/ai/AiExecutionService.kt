package com.myfitai.app.ai

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

/**
 * Provider -> structured envelope -> local validators -> authoritative app business validator.
 *
 * Paid-call ceiling per operation: at most `1 + maxSchemaRetries` (<= 3) provider responses. A transient
 * transport error before any response was received is rethrown so WorkManager may retry the whole job
 * (at most [com.myfitai.app.domain.ai.AiJobWorker.MAX_RETRY_ATTEMPTS] extra attempts, none of which received a
 * billed response). Once a response was already consumed (schema/business retry), a later transient error is
 * NOT retried by the job: it becomes [Failure.PaidRetryBudgetConsumed], so a restart can never re-spend the
 * schema/business retry budget.
 *
 * Every failure is one of the categories of [AiFailure] (the only classification). The contract of a request
 * is looked up by exact schemaName in [AiContractRegistry]: an unregistered schema fails with
 * [Failure.ContractNotRegistered] before any provider call. Diagnostics never contain model text: only
 * schemaName, outcome category, attempt and duration ([AiDiagnosticEvent]).
 */
class AiExecutionService(
    private val contracts: (String) -> AiContractRegistry.Spec? = AiContractRegistry::find,
    private val diagnostics: (AiDiagnosticEvent) -> Unit = AiDiagnostics::log,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    data class ValidatedResponse(
        val provider: AiProviderType,
        val model: String,
        val jsonText: String,
        val usage: AiUsageMetadata? = null,
    )

    /** Typed exceptions whose message is always a stable code (never model text). Classified by [AiFailure.classify]. */
    sealed class Failure(message: String) : Exception(message) {
        class InvalidSchema(val errors: List<String>) : Failure("INVALID_SCHEMA")
        class OutputTruncated : Failure("OUTPUT_TRUNCATED")
        class BusinessRejected(val reason: String) : Failure(reason)
        /** Refusal, safety block, blocked prompt or content filter. The provider text is not kept. */
        class Refused(val reason: AiFailure.RefusalReason) : Failure("REFUSED")
        class EmptyOutput : Failure("EMPTY_OUTPUT")
        class MalformedJson : Failure("MALFORMED_JSON")
        /** The request used a schemaName absent from [AiContractRegistry]. [schemaName] is chosen by the app, not the model. */
        class ContractNotRegistered(val schemaName: String) : Failure("CONTRACT_NOT_REGISTERED")
        /** A transient transport error happened after a paid response was already consumed: do not restart the job. */
        class PaidRetryBudgetConsumed(cause: Throwable) : Failure("TRANSIENT_AFTER_PAID_RETRY") {
            init { initCause(cause) }
        }
    }

    suspend fun execute(
        provider: AiProvider,
        request: AiStructuredRequest,
        maxSchemaRetries: Int = 1,
        businessValidator: (String) -> Result<Unit> = { Result.success(Unit) },
    ): ValidatedResponse {
        require(maxSchemaRetries in 0..2)
        val startedAt = nowMillis()
        var attempt = 0
        fun report(outcome: String) = diagnostics(AiDiagnosticEvent(request.schemaName, outcome, attempt, nowMillis() - startedAt))
        try {
            val spec = contracts(request.schemaName) ?: throw Failure.ContractNotRegistered(request.schemaName)
            val compact = spec.format == AiContractRegistry.Format.COMPACT_PIPE
            var currentRequest = request
            while (true) {
                val raw = try {
                    provider.generateStructured(currentRequest)
                } catch (error: AiTransportException) {
                    if (attempt > 0 && AiFailure.classify(error).retryable) throw Failure.PaidRetryBudgetConsumed(error)
                    throw error
                }
                // An abnormal end (token limit, safety/refusal, unknown reason) is never a success and never a
                // schema/business failure: an incomplete or blocked answer must not be persisted or retried blindly.
                AiFailure.fromFinishReason(raw.finishReason)?.let { throw it.toException() }
                if (raw.jsonText.isBlank()) throw Failure.EmptyOutput()

                val parseable = try {
                    JSONObject(raw.jsonText)
                    true
                } catch (_: JSONException) {
                    false
                }
                val schemaResult = CanonicalJsonSchemaValidator.validate(raw.jsonText, request.schemaJson)
                if (!schemaResult.valid) {
                    if (attempt >= maxSchemaRetries) {
                        throw if (parseable) Failure.InvalidSchema(schemaResult.errors) else Failure.MalformedJson()
                    }
                    attempt++
                    currentRequest = request.copy(
                        userPrompt = request.userPrompt + if (compact) {
                            "\nFIX: return exactly one JSON object with exactly one property named data, whose value is a string containing the pipe protocol. ${spec.retryRule()} Do not include schema, status, metadata or any other property. Return no prose or markdown."
                        } else {
                            "\nPrevious output was INVALID_SCHEMA. Return exactly one valid JSON object. Do not use markdown. Do not wrap the JSON in code fences. Do not add text before or after the JSON. All strings must be valid JSON strings with escaped special characters."
                        }
                    )
                    continue
                }

                val business = businessValidator(raw.jsonText)
                if (business.isFailure) {
                    val reason = business.exceptionOrNull()?.message ?: "BUSINESS_VALIDATION_FAILED"
                    if (compact && attempt < maxSchemaRetries) {
                        attempt++
                        currentRequest = request.copy(userPrompt = request.userPrompt + "\nFIX:${reason.take(80)}. Return exactly one JSON envelope with one data property. ${spec.retryRule()} No markdown or code fences.")
                        continue
                    }
                    throw Failure.BusinessRejected(reason)
                }
                report("OK")
                return ValidatedResponse(raw.provider, raw.model, raw.jsonText, raw.usage)
            }
        } catch (cancelled: CancellationException) {
            report(AiFailure.Cancelled.code)
            throw cancelled
        } catch (error: Throwable) {
            report(AiFailure.classify(error).code)
            throw error
        }
    }
}

/** What is logged about an AI call: no prompt, no response, no key, no provider text. */
data class AiDiagnosticEvent(
    val schemaName: String,
    /** `OK` or the stable [AiFailure.code] of the failure. */
    val outcome: String,
    /** Number of schema/business retries already spent (0 = first response was final). */
    val attempt: Int,
    val durationMs: Long,
)

internal object AiDiagnostics {
    private const val TAG = "MyFitAiAi"

    fun format(event: AiDiagnosticEvent): String =
        "schemaName=${event.schemaName} outcome=${event.outcome} attempt=${event.attempt} durationMs=${event.durationMs}"

    fun log(event: AiDiagnosticEvent) {
        try {
            if (event.outcome == "OK") android.util.Log.i(TAG, format(event)) else android.util.Log.w(TAG, format(event))
        } catch (_: RuntimeException) {
            // android.util.Log is not available in plain JVM unit tests; diagnostics must never break an AI call.
        }
    }
}
