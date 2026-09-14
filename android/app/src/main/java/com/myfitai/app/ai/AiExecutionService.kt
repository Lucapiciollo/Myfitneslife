package com.myfitai.app.ai

/** Provider -> structured envelope -> local validators -> authoritative app business validator. */
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
        val compact = request.schemaName.contains("_pipe_")
        var attempt = 0
        var currentRequest = request
        while (true) {
            val raw = provider.generateStructured(currentRequest)
            val schemaResult = CanonicalJsonSchemaValidator.validate(raw.jsonText, request.schemaJson)
            if (!schemaResult.valid) {
                if (attempt >= maxSchemaRetries) throw Failure.InvalidSchema(schemaResult.errors)
                attempt++
                currentRequest = request.copy(
                    userPrompt = request.userPrompt + if (compact) {
                        "\nFIX: invalid envelope. Return exact schema + pipe protocol only."
                    } else {
                        "\nPrevious output was INVALID_SCHEMA. Return ONLY JSON matching the supplied schema exactly; do not add fields or markdown."
                    }
                )
                continue
            }

            val business = businessValidator(raw.jsonText)
            if (business.isFailure) {
                val reason = business.exceptionOrNull()?.message ?: "BUSINESS_VALIDATION_FAILED"
                if (compact && attempt < maxSchemaRetries) {
                    attempt++
                    currentRequest = request.copy(
                        userPrompt = request.userPrompt + "\nFIX:${reason.take(80)}. Regenerate exact pipe records only."
                    )
                    continue
                }
                throw Failure.BusinessRejected(reason)
            }
            return ValidatedResponse(raw.provider, raw.model, raw.jsonText)
        }
    }
}
