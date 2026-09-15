package com.myfitai.app.ai

/** Image payload kept only in memory for the current AI request. Never persist or log its data. */
data class AiImageInput(
    val mimeType: String,
    val base64Data: String,
)

/** Canonical request shared by every provider: providers must not mutate its semantic content. */
data class AiStructuredRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val schemaName: String,
    val schemaJson: String,
    /** Optional provider-only schema; schemaJson remains the local canonical authority. */
    val remoteSchemaJson: String? = null,
    val maxOutputTokens: Int = 8_000,
    val image: AiImageInput? = null,
    /** Optional Gemini thinking budget in tokens (0 disables thinking). Ignored by providers without thinking. */
    val thinkingBudget: Int? = null,
    /** When false, the provider requests JSON but relies on the canonical local validator for schema enforcement. */
    val useNativeSchema: Boolean = true,
    /** Schema fallback is intentionally opt-in for the weekly-plan workload only. */
    val allowSchemaFallback: Boolean = false,
)

data class AiRawResponse(
    val provider: AiProviderType,
    val model: String,
    val jsonText: String,
    val usage: AiUsageMetadata? = null,
    val finishReason: String? = null,
)

data class AiUsageMetadata(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val thoughtsTokens: Long? = null,
) {
    fun display(): String {
        val input = inputTokens?.toString() ?: "?"
        val output = outputTokens?.toString() ?: "?"
        val total = totalTokens?.toString() ?: "?"
        return "Token: input $input · output $output · totale $total"
    }
}

enum class AiTransportFailureKind {
    INVALID_API_KEY,
    UNSUPPORTED_AUTH_METHOD,
    MODEL_UNAVAILABLE,
    QUOTA_EXHAUSTED,
    RATE_LIMITED,
    SCHEMA,
    NETWORK,
    PROVIDER_UNAVAILABLE,
    UNKNOWN,
}

internal object AiTransportFailureClassifier {
    fun classify(status: Int, responseBody: String): AiTransportFailureKind {
        val body = responseBody.lowercase()
        // Auth Key (AQ.) recognized but rejected by the endpoint as an unsupported credential type.
        // This is NOT an invalid key: the value is accepted as a credential but the auth path is refused
        // (e.g. Auth Key bound to a project/org that does not allow REST API-key access).
        if (body.contains("access_token_type_unsupported") ||
            body.contains("access token type") ||
            (status == 401 && (body.contains("oauth") || body.contains("access token") || body.contains("login cookie")))
        ) {
            return AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD
        }
        if ((body.contains("model") && (body.contains("not found") || body.contains("not available") || body.contains("does not exist"))) ||
            body.contains("access to this model is not available")
        ) return AiTransportFailureKind.MODEL_UNAVAILABLE
        if (body.contains("api key not valid") || body.contains("api_key_invalid") ||
            body.contains("invalid api key") || body.contains("api key expired") || body.contains("api_key_invalid")
        ) {
            return AiTransportFailureKind.INVALID_API_KEY
        }
        if (status == 401) {
            return AiTransportFailureKind.INVALID_API_KEY
        }
        if (status == 403 && (body.contains("permission") || body.contains("unauthorized"))) {
            return AiTransportFailureKind.INVALID_API_KEY
        }
        if (status == 429 && listOf(
                "quota",
                "quota exceeded",
                "quota_exceeded",
                "resource exhausted",
                "resource_exhausted",
                "billing",
            ).any(body::contains)
        ) {
            return AiTransportFailureKind.QUOTA_EXHAUSTED
        }
        if (status == 429 || body.contains("rate limit") || body.contains("too many requests")) {
            return AiTransportFailureKind.RATE_LIMITED
        }
        if (body.contains("schema") || body.contains("invalid argument") || body.contains("malformed request") ||
            body.contains("request contains an invalid argument")
        ) {
            return AiTransportFailureKind.SCHEMA
        }
        if (status in 500..599) {
            return AiTransportFailureKind.PROVIDER_UNAVAILABLE
        }
        return AiTransportFailureKind.UNKNOWN
    }
}

sealed class AiTransportException(message: String) : Exception(message) {
    class NotConfigured(provider: AiProviderType) : AiTransportException("Provider $provider non configurato")
    class Http(
        val provider: AiProviderType,
        val statusCode: Int,
        val failureKind: AiTransportFailureKind,
        val retryAfterSeconds: Long? = null,
        val quotaLimit: Int? = null,
    ) : AiTransportException("Errore HTTP provider: $statusCode")
    class Network : AiTransportException("Provider non raggiungibile")
    class InvalidResponse : AiTransportException("Risposta provider non interpretabile")
}
