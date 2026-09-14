package com.myfitai.app.ai

/**
 * Provider-neutral contract. Gemini and OpenAI receive the same semantic request.
 * Runtime agents normally use a tiny structured JSON envelope whose `data` field contains
 * the versioned MyFitAI pipe payload; local parsers and business validators remain authoritative.
 */
interface AiProvider {
    val type: AiProviderType
    suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse
}
