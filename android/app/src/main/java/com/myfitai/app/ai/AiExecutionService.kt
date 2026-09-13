package com.myfitai.app.ai

/**
 * Provider -> JSON -> local schema validator -> authoritative app business validator.
 * Provider output is never normalized silently: invalid output is rejected.
 */
class AiExecutionService {
    data class ValidatedResponse(
        val provider: AiProviderType,
        val model: String,
        val jsonText: String,
    )

    sealed class Failure(message: String) : Exception(message) {
        class InvalidSchema(val errors: List<String>) : Failure("INVALID_SCHEMA")
        class BusinessRejected(val reason: String) : Failure(reason)
    }

    suspend fun execute(
        provider: AiProvider,
        request: AiStructuredRequest,
        maxSchemaRetries: Int = 1,
        businessValidator: (String) -> Result<Unit> = { Result.success(Unit) },
    ): ValidatedResponse {
        require(maxSchemaRetries in 0..2)
        var attempt = 0
        var currentRequest = request
        while (true) {
            val raw = provider.generateStructured(currentRequest)
            val schemaResult = CanonicalJsonSchemaValidator.validate(raw.jsonText, request.schemaJson)
            if (!schemaResult.valid) {
                if (attempt >= maxSchemaRetries) throw Failure.InvalidSchema(schemaResult.errors)
                attempt++
                currentRequest = request.copy(
                    userPrompt = request.userPrompt +
                        "\n\nPrevious output was INVALID_SCHEMA. Return ONLY JSON matching the supplied schema exactly; do not add fields or markdown."
                )
                continue
            }

            val business = businessValidator(raw.jsonText)
            if (business.isFailure) {
                throw Failure.BusinessRejected(business.exceptionOrNull()?.message ?: "BUSINESS_VALIDATION_FAILED")
            }
            return ValidatedResponse(raw.provider, raw.model, raw.jsonText)
        }
    }
}
