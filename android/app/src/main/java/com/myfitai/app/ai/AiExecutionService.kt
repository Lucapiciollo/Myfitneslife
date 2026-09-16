package com.myfitai.app.ai

/** Provider -> structured envelope -> local validators -> authoritative app business validator. */
class AiExecutionService {
    data class ValidatedResponse(
        val provider: AiProviderType,
        val model: String,
        val jsonText: String,
        val usage: AiUsageMetadata? = null,
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
                if (provider is GeminiByokProvider) {
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
                currentRequest = request.copy(
                    userPrompt = request.userPrompt + if (compact) {
                        "\nFIX: invalid envelope. Return exact schema + pipe protocol only."
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
                    val version = request.schemaName.substringBefore("_pipe_").let {
                        when {
                            it.contains("weekly_nutrition") -> "MFP1"
                            it.contains("cheat_understanding") -> "CU1"
                            it.contains("cheat_adjustment") -> "CA1"
                            it.contains("meal_alternatives") -> "MA1"
                            it.contains("nutrition_advice") -> "NA1"
                            it.contains("weekly_review") -> "WR1"
                            it.contains("bia") -> "BIA1"
                            else -> "?"
                        }
                    }
                    android.util.Log.w(
                        "MyFitAiCompactValidation",
                        "schemaName=${request.schemaName} reason=${reason.take(120)} " +
                            AiCompactDiagnostics.describe(raw.jsonText, version),
                    )
                }
                if (compact && attempt < maxSchemaRetries) {
                    attempt++
                    currentRequest = request.copy(
                        userPrompt = request.userPrompt + "\nFIX:${reason.take(80)}. Regenerate exact pipe records only."
                    )
                    continue
                }
                throw Failure.BusinessRejected(reason)
            }
            return ValidatedResponse(raw.provider, raw.model, raw.jsonText, raw.usage)
        }
    }
}
