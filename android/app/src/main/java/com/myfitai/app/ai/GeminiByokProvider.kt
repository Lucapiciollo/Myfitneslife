package com.myfitai.app.ai

import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import org.json.JSONArray
import org.json.JSONObject

/** Direct Gemini Developer API transport. Firebase AI Logic is intentionally not involved. */
class GeminiByokProvider internal constructor(
    private val credentialStore: SecureAiCredentialStore,
    private val primaryModel: String = AiModelConfig.GEMINI_PRIMARY,
    private val fallbackModel: String = AiModelConfig.GEMINI_FALLBACK,
    private val interactionsTransport: GeminiInteractionsTransport = GeminiInteractionsTransport(),
) : AiProvider {
    override val type: AiProviderType = AiProviderType.GEMINI

    override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
        val apiKey = credentialStore.read(AiCredentialProvider.GEMINI)?.takeIf { it.isNotBlank() }
            ?: throw AiTransportException.NotConfigured(type)
        return generateWithKey(apiKey, request)
    }

    suspend fun verifyApiKey(apiKey: String): AiRawResponse {
        return try {
            verifyWithKey(apiKey.trim())
        } catch (_: AiExecutionService.Failure) {
            // The HTTP call succeeded but the probe answer was empty/blocked/cut: verification only needs a readable reply.
            throw AiTransportException.InvalidResponse()
        }
    }

    private suspend fun verifyWithKey(apiKey: String): AiRawResponse {
        return generateWithKey(apiKey, AiStructuredRequest(
            systemPrompt = "Return only a JSON object with status set to ok.",
            userPrompt = "Reply with {\"status\":\"ok\"}.",
            schemaName = "myfitai_provider_verification",
            schemaJson = "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"ok\"]}},\"required\":[\"status\"],\"additionalProperties\":false}",
            // Room for a few thinking tokens: the cap now reaches generateContent and must not starve the tiny answer.
            maxOutputTokens = 256,
            // Do not send thinkingConfig during credential verification. Some Gemini models
            // reject an explicit zero budget even when thinking is not otherwise requested.
            thinkingBudget = null,
            // Key verification must not depend on provider-specific native schema support.
            // The response is still JSON-only and is validated locally by the caller.
            useNativeSchema = false,
        ))
    }

    private suspend fun generateWithKey(apiKey: String, request: AiStructuredRequest): AiRawResponse {
        require(apiKey.isNotBlank()) { "Gemini API key must not be blank" }
        return try {
            generateWithModel(apiKey, primaryModel, request)
        } catch (error: AiTransportException.Http) {
            if (error.provider == type && error.failureKind == AiTransportFailureKind.MODEL_REQUIRES_INTERACTIONS) {
                interactionsTransport.generate(apiKey, primaryModel, request)
            } else if (error.provider == type && error.failureKind == AiTransportFailureKind.MODEL_UNAVAILABLE && primaryModel != fallbackModel) {
                generateWithModel(apiKey, fallbackModel, request)
            } else if (error.provider == type && error.failureKind == AiTransportFailureKind.SCHEMA &&
                request.useNativeSchema && request.allowSchemaFallback
            ) {
                generateWithModel(apiKey, primaryModel, request.copy(useNativeSchema = false))
            } else {
                throw error
            }
        }
    }

    private suspend fun generateWithModel(apiKey: String, model: String, request: AiStructuredRequest): AiRawResponse {
        val mappedSchema = if (request.useNativeSchema) {
            GeminiSchemaMapper.map(request.remoteSchemaJson ?: request.schemaJson).also { logSchemaDiagnostics(request.schemaName, it, "NATIVE") }
        } else {
            if (credentialStore.isDebuggable()) {
                android.util.Log.d("MyFitAiGeminiSchema", "schemaName=${request.schemaName} schemaMode=JSON_ONLY")
            }
            null
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))))
            .put("contents", JSONArray().put(buildContent(request)))
            .put("generationConfig", buildGenerationConfig(request, mappedSchema?.schema))
        val raw = HttpJsonClient.post(
            provider = type,
            url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            headers = mapOf("x-goog-api-key" to apiKey),
            body = body.toString(),
        )
        val result = try {
            parseGenerateContent(raw, model)
        } catch (error: AiExecutionService.Failure) {
            // Category only: never the provider text.
            if (credentialStore.isDebuggable()) {
                android.util.Log.d("MyFitAiGeminiResponse", "model=$model failure=${AiFailure.classify(error).code}")
            }
            throw error
        }
        if (credentialStore.isDebuggable()) {
            android.util.Log.d(
                "MyFitAiGeminiResponse",
                "model=$model finishReason=${result.finishReason ?: "-"} " +
                    "promptTokenCount=${result.usage?.inputTokens ?: "-"} " +
                    "candidatesTokenCount=${result.usage?.outputTokens ?: "-"} " +
                    "cachedContentTokenCount=${result.usage?.cachedTokens ?: "-"} " +
                    "totalTokenCount=${result.usage?.totalTokens ?: "-"}",
            )
        }
        return result
    }

    private fun logSchemaDiagnostics(schemaName: String, mapped: GeminiSchemaMapper.Result, schemaMode: String) {
        if (!credentialStore.isDebuggable()) return
        android.util.Log.d(
            "MyFitAiGeminiSchema",
            "schemaName=$schemaName schemaMode=$schemaMode canonicalSchemaLength=${mapped.canonicalLength} mappedSchemaLength=${mapped.mappedLength} " +
                "maxDepth=${mapped.maxDepth} propertyCount=${mapped.propertyCount} arrayCount=${mapped.arrayCount}",
        )
    }

    private fun buildContent(request: AiStructuredRequest): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", request.userPrompt))
        request.image?.let { image ->
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", image.mimeType).put("data", image.base64Data)))
        }
        return JSONObject().put("role", "user").put("parts", parts)
    }

    companion object {
        /** generateContent `generationConfig`: JSON mime type, optional native schema, thinking budget and the output cap. */
        internal fun buildGenerationConfig(request: AiStructuredRequest, mappedSchema: JSONObject?): JSONObject {
            val generationConfig = JSONObject().put("responseMimeType", "application/json")
            mappedSchema?.let { generationConfig.put("responseSchema", it) }
            request.thinkingBudget?.let { budget ->
                generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
            }
            // Same budget OpenAI (`max_output_tokens`) and the Interactions transport already receive.
            request.maxOutputTokens?.let { generationConfig.put("maxOutputTokens", it) }
            return generationConfig
        }

        /**
         * Pure parser of a generateContent body (no network). Anything that is not a complete, readable answer is
         * a typed failure: prompt blocked / SAFETY and similar finish reasons -> Refused, MAX_TOKENS -> Truncated
         * (even when partial text is present), no text -> EmptyOutput, unknown finish reason -> protocol error.
         * Provider text (blockReasonMessage, safety ratings) is never copied into an exception.
         */
        internal fun parseGenerateContent(raw: String, model: String): AiRawResponse {
            val response = try {
                JSONObject(raw)
            } catch (_: org.json.JSONException) {
                throw AiTransportException.InvalidResponse()
            }
            val blockReason = response.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            if (blockReason.isNotBlank() && blockReason != "BLOCK_REASON_UNSPECIFIED") {
                throw AiExecutionService.Failure.Refused(AiFailure.RefusalReason.PROMPT_BLOCKED)
            }
            val finishReason = response.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")?.takeIf { it.isNotBlank() }
            AiFailure.fromFinishReason(finishReason)?.let { throw it.toException() }
            val text = extractText(response) ?: throw AiExecutionService.Failure.EmptyOutput()
            return AiRawResponse(
                provider = AiProviderType.GEMINI,
                model = model,
                jsonText = text,
                usage = response.optJSONObject("usageMetadata")?.let { usage ->
                    AiUsageMetadata(
                        inputTokens = usage.optLong("promptTokenCount").takeIf { usage.has("promptTokenCount") },
                        outputTokens = usage.optLong("candidatesTokenCount").takeIf { usage.has("candidatesTokenCount") },
                        totalTokens = usage.optLong("totalTokenCount").takeIf { usage.has("totalTokenCount") },
                        thoughtsTokens = usage.optLong("thoughtsTokenCount").takeIf { usage.has("thoughtsTokenCount") },
                        cachedTokens = usage.optLong("cachedContentTokenCount").takeIf { usage.has("cachedContentTokenCount") },
                    )
                },
                finishReason = finishReason,
            )
        }

        private fun extractText(root: JSONObject): String? {
            val candidates = root.optJSONArray("candidates") ?: return null
            for (i in 0 until candidates.length()) {
                val parts = candidates.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                // A complete structured response can span multiple text parts: taking only the
                // first part silently truncates the weekly-plan envelope.
                val text = buildString {
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (!part.optBoolean("thought", false)) {
                            part.optString("text").let { append(it) }
                        }
                    }
                }
                if (text.isNotBlank()) return text
            }
            return null
        }
    }
}
