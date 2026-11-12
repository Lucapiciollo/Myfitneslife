package com.myfitai.app.ai

/**
 * Provider -> structured envelope -> local validators -> authoritative app business validator.
 *
 * Paid-call ceiling per operation: at most `1 + maxSchemaRetries` (<= 3) provider responses. A transient
 * transport error before any response was received is rethrown so WorkManager may retry the whole job
 * (at most [com.myfitai.app.domain.ai.AiJobWorker.MAX_RETRY_ATTEMPTS] extra attempts, none of which received a
 * billed response). Once a response was already consumed (schema/business retry), a later transient error is
 * NOT retried by the job: it becomes [Failure.PaidRetryBudgetConsumed], so a restart can never re-spend the
 * schema/business retry budget.
 */
class AiExecutionService {
    data class ValidatedResponse(
        val provider: AiProviderType,
        val model: String,
        val jsonText: String,
        val usage: AiUsageMetadata? = null,
    )

    sealed class Failure(message: String) : Exception(message) {
        class InvalidSchema(val errors: List<String>) : Failure("INVALID_SCHEMA")
        class OutputTruncated : Failure("OUTPUT_TRUNCATED")
        class BusinessRejected(val reason: String) : Failure(reason)
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
        val compact = request.schemaName.contains("_pipe_")
        var attempt = 0
        var currentRequest = request
        while (true) {
            val raw = try {
                provider.generateStructured(currentRequest)
            } catch (error: AiTransportException) {
                if (attempt > 0 && error.isTransient()) throw Failure.PaidRetryBudgetConsumed(error)
                throw error
            }
            // A token-limited answer is incomplete, not a schema/business failure.
            // Never persist an incomplete nutrition plan or retry the same inadequate budget.
            if (raw.finishReason == "MAX_TOKENS" || raw.finishReason == "max_output_tokens") {
                throw Failure.OutputTruncated()
            }
            val schemaResult = CanonicalJsonSchemaValidator.validate(raw.jsonText, request.schemaJson)
            if (!schemaResult.valid) {
                if (provider.type == AiProviderType.GEMINI) {
                    val text = raw.jsonText
                    val diagnostics = runCatching {
                        org.json.JSONObject(text)
                    }.exceptionOrNull()?.let { error ->
                        if (error is org.json.JSONException) JsonParseDiagnostics.diagnose(text, error) else null
                    }
                    val summary = diagnostics?.let {
                        "outputLength=${it.outputLength} outputShape=${it.outputShape} exceptionType=${it.exceptionType} " +
                            "exceptionMessage=${it.exceptionMessage} parserPosition=${it.parserPosition ?: "-"} " +
                            "category=${it.category} leadingFence=${it.hasLeadingFence} trailingFence=${it.hasTrailingFence} " +
                            "startsObject=${it.startsWithObjectBrace} endsObject=${it.endsWithObjectBrace} nullByte=${it.containsNullByte} " +
                            "bom=${it.containsBom} trailingAfterObject=${it.hasTrailingCharactersAfterObject} " +
                            "firstCode=${it.firstCharCode ?: "-"} lastCode=${it.lastCharCode ?: "-"} " +
                            "firstNonWhitespaceCode=${it.firstNonWhitespaceCharCode ?: "-"} lastNonWhitespaceCode=${it.lastNonWhitespaceCharCode ?: "-"}"
                    }.orEmpty()
                    android.util.Log.w(
                        "MyFitAiCanonicalValidation",
                        "schemaName=${request.schemaName} $summary errors=${schemaResult.errors.take(12).joinToString(" | ").take(1200)}",
                    )
                }
                if (attempt >= maxSchemaRetries) throw Failure.InvalidSchema(schemaResult.errors)
                attempt++
                val compactVersion = compactProtocolVersion(request.schemaName)
                val retryRule = compactVersion?.let { "The data value must begin with $it and follow its requested pipe records, one record per line." }
                    ?: "The data value must follow its requested compact protocol, one record per line."
                currentRequest = request.copy(
                    userPrompt = request.userPrompt + if (compact) {
                        "\nFIX: return exactly one JSON object with exactly one property named data, whose value is a string containing the pipe protocol. $retryRule Do not include schema, status, metadata or any other property. Return no prose or markdown."
                    } else {
                        "\nPrevious output was INVALID_SCHEMA. Return exactly one valid JSON object. Do not use markdown. Do not wrap the JSON in code fences. Do not add text before or after the JSON. All strings must be valid JSON strings with escaped special characters."
                    }
                )
                continue
            }

            val business = businessValidator(raw.jsonText)
            if (business.isFailure) {
                val reason = business.exceptionOrNull()?.message ?: "BUSINESS_VALIDATION_FAILED"
                if (compact && provider.type == AiProviderType.GEMINI) {
                    val version = compactProtocolVersion(request.schemaName) ?: "?"
                    android.util.Log.w(
                        "MyFitAiCompactValidation",
                        "schemaName=${request.schemaName} reason=${reason.take(120)} " +
                            AiCompactDiagnostics.describe(raw.jsonText, version),
                    )
                }
                if (compact && attempt < maxSchemaRetries) {
                    attempt++
                    val compactVersion = compactProtocolVersion(request.schemaName)
                    val retryRule = compactVersion?.let { "The data value must begin with $it and follow its requested pipe records, one record per line." }
                        ?: "The data value must follow its requested compact protocol, one record per line."
                    currentRequest = request.copy(userPrompt = request.userPrompt + "\nFIX:${reason.take(80)}. Return exactly one JSON envelope with one data property. $retryRule No markdown or code fences.")
                    continue
                }
                throw Failure.BusinessRejected(reason)
            }
            return ValidatedResponse(raw.provider, raw.model, raw.jsonText, raw.usage)
        }
    }

    /** Exact schemaName -> protocol header. Substring matching mislabelled bia_raw (B2) and progress_analysis (PA1). */
    private fun compactProtocolVersion(schemaName: String): String? = COMPACT_PROTOCOLS[schemaName]

    companion object {
        /** Exact schemaName -> protocol header; the single source of truth (also checked by tests against the contracts). */
        internal val COMPACT_PROTOCOLS: Map<String, String> = mapOf(
            "myfitai_bia_raw_pipe_v2" to "B2",
            "myfitai_bia_pipe_v1" to "BIA1",
            "myfitai_bia_progress_coach_pipe_v2" to "BA2",
            "myfitai_progress_analysis_pipe_v1" to "PA1",
            "myfitai_body_proportion_pipe_v1" to "BP1",
            "myfitai_weekly_review_pipe_v1" to "WR1",
            "myfitai_meal_alternatives_pipe_v1" to "MA1",
            "myfitai_nutrition_advice_pipe_v1" to "NA1",
            "myfitai_cheat_understanding_pipe_v1" to "CU1",
            "myfitai_cheat_adjustment_pipe_v1" to "CA1",
            "myfitai_plan_review_pipe_v1" to "PR1",
            "myfitai_nutrition_path_pipe_v1" to "NP1",
            "myfitai_weekly_nutrition_pipe_v1" to "MFP1",
        )
    }
}
