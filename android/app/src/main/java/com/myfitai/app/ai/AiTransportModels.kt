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
    val maxOutputTokens: Int = 8_000,
    val image: AiImageInput? = null,
)

data class AiRawResponse(
    val provider: AiProviderType,
    val model: String,
    val jsonText: String,
)

sealed class AiTransportException(message: String) : Exception(message) {
    class NotConfigured(provider: AiProviderType) : AiTransportException("Provider $provider non configurato")
    class Http(val statusCode: Int) : AiTransportException("Errore HTTP provider: $statusCode")
    class InvalidResponse : AiTransportException("Risposta provider non interpretabile")
}
