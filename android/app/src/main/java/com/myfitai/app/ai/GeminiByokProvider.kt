package com.myfitai.app.ai

import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import org.json.JSONArray
import org.json.JSONObject

/** Direct Gemini Developer API transport. Firebase AI Logic is intentionally not involved. */
class GeminiByokProvider(
    private val credentialStore: SecureAiCredentialStore,
    private val primaryModel: String = AiModelConfig.GEMINI_PRIMARY,
    private val fallbackModel: String = AiModelConfig.GEMINI_FALLBACK,
) : AiProvider {
    override val type: AiProviderType = AiProviderType.GEMINI

    override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
        val apiKey = credentialStore.read(AiCredentialProvider.GEMINI)?.takeIf { it.isNotBlank() }
            ?: throw AiTransportException.NotConfigured(type)
        return generateWithKey(apiKey, request)
    }

    suspend fun verifyApiKey(apiKey: String): AiRawResponse {
        return generateWithKey(apiKey.trim(), AiStructuredRequest(
            systemPrompt = "Return only the string ok.",
            userPrompt = "Reply with ok.",
            schemaName = "myfitai_provider_verification",
            schemaJson = "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"ok\"]}},\"required\":[\"status\"],\"additionalProperties\":false}",
            maxOutputTokens = 256,
            thinkingBudget = 0,
        ))
    }

    private suspend fun generateWithKey(apiKey: String, request: AiStructuredRequest): AiRawResponse {
        require(apiKey.isNotBlank()) { "Gemini API key must not be blank" }
        return try {
            generateWithModel(apiKey, primaryModel, request)
        } catch (error: AiTransportException.Http) {
            if (error.provider == type && error.failureKind == AiTransportFailureKind.MODEL_UNAVAILABLE && primaryModel != fallbackModel) {
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
        val generationConfig = JSONObject().put("responseMimeType", "application/json")
        if (request.useNativeSchema) {
            val mapped = GeminiSchemaMapper.map(request.remoteSchemaJson ?: request.schemaJson)
            logSchemaDiagnostics(request.schemaName, mapped, "NATIVE")
            generationConfig.put("responseSchema", mapped.schema)
        } else if (credentialStore.isDebuggable()) {
            android.util.Log.d("MyFitAiGeminiSchema", "schemaName=${request.schemaName} schemaMode=JSON_ONLY")
        }
        request.thinkingBudget?.let { budget ->
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))))
            .put("contents", JSONArray().put(buildContent(request)))
            .put("generationConfig", generationConfig)
        val raw = HttpJsonClient.post(
            provider = type,
            url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            headers = mapOf("x-goog-api-key" to apiKey),
            body = body.toString(),
        )
        val response = JSONObject(raw)
        return AiRawResponse(
            provider = type,
            model = model,
            jsonText = extractText(response) ?: throw AiTransportException.InvalidResponse(),
            usage = response.optJSONObject("usageMetadata")?.let { usage ->
                AiUsageMetadata(
                    inputTokens = usage.optLong("promptTokenCount").takeIf { usage.has("promptTokenCount") },
                    outputTokens = usage.optLong("candidatesTokenCount").takeIf { usage.has("candidatesTokenCount") },
                    totalTokens = usage.optLong("totalTokenCount").takeIf { usage.has("totalTokenCount") },
                    thoughtsTokens = usage.optLong("thoughtsTokenCount").takeIf { usage.has("thoughtsTokenCount") },
                    cachedTokens = usage.optLong("cachedContentTokenCount").takeIf { usage.has("cachedContentTokenCount") },
                )
            },
            finishReason = response.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")?.takeIf { it.isNotBlank() },
        ).also { result ->
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
        }
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

    private fun extractText(root: JSONObject): String? {
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) parts.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
