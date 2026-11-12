package com.myfitai.app.ai

/**
 * An AI output that has passed BOTH the canonical schema validation and the authoritative business validation.
 *
 * The constructor is private and there is no `copy`: the only way to obtain an instance is [validate], so any
 * persistence/UI API that accepts a `ValidatedOutput<T>` (instead of a raw JSON string) is guaranteed, at compile
 * time, to receive data that was validated. Never accept `String`/`JSONObject` model output in new flows.
 *
 * Integration (tranche C3): `AiExecutionService.executeValidated` / `AiRuntimeGateway.executeValidated` run
 * [validate] on every provider response and map [Rejection.Schema] to `Failure.InvalidSchema`/`MalformedJson`
 * (category `AiFailure.SchemaInvalid`/`MalformedJson`) and [Rejection.Business] to `Failure.BusinessRejected`
 * (category `AiFailure.BusinessRuleViolated`). The legacy `execute` (ValidatedResponse + `Result<Unit>` validator)
 * shares the same validation step, so handlers migrate to `executeValidated` one at a time.
 *
 * @property value the parsed, business-validated domain object.
 * @property schemaName name of the contract the output was validated against (diagnostics only).
 */
class ValidatedOutput<out T> private constructor(
    val value: T,
    val schemaName: String,
) {
    /** Why an output could not become a [ValidatedOutput]. Carries no model text, only paths/categories/codes. */
    sealed class Rejection(message: String) : Exception(message) {
        /** Schema validation failed; [issues] hold only field paths and categories. */
        class Schema(val issues: List<CanonicalJsonSchemaValidator.Issue>) : Rejection("INVALID_SCHEMA")

        /** Business validation failed; [reason] is a stable code produced by the app's own validator. */
        class Business(val reason: String) : Rejection(reason)
    }

    override fun toString(): String = "ValidatedOutput(schemaName=$schemaName)" // never print the payload

    companion object {
        /**
         * Validates [jsonText] against [schemaJson] and then runs [business] (which parses the JSON into [T] and
         * applies the app's business rules). Returns success only if both pass; otherwise a [Rejection].
         * [business] is invoked only after the schema is valid. An exception thrown by it becomes [Rejection.Business].
         */
        fun <T> validate(
            jsonText: String,
            schemaJson: String,
            schemaName: String,
            business: (String) -> Result<T>,
        ): Result<ValidatedOutput<T>> {
            val schema = CanonicalJsonSchemaValidator.validate(jsonText, schemaJson)
            if (!schema.valid) return Result.failure(Rejection.Schema(schema.issues))
            val parsed = try {
                business(jsonText)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return Result.failure(Rejection.Business(error.message ?: "BUSINESS_VALIDATION_FAILED"))
            }
            return parsed.fold(
                onSuccess = { Result.success(ValidatedOutput(it, schemaName)) },
                onFailure = { Result.failure(Rejection.Business(it.message ?: "BUSINESS_VALIDATION_FAILED")) },
            )
        }
    }
}
