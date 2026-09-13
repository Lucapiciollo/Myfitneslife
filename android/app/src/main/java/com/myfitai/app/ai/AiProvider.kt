package com.myfitai.app.ai

/**
 * Provider-neutral contract. Both Gemini and OpenAI receive the same canonical
 * prompts/schema and must return only the structured JSON payload.
 */
interface AiProvider {
    val type: AiProviderType
    suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse
}
