package com.myfitai.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Stateless Gemini Interactions API transport used for models that require it. */
internal class GeminiInteractionsTransport(
    private val post: suspend (provider: AiProviderType, url: String, headers: Map<String, String>, body: String) -> String =
        { provider, url, headers, body -> HttpJsonClient.post(provider, url, headers, body) },
) {
    suspend fun generate(
        apiKey: String,
        model: String,
        request: AiStructuredRequest,
    ): AiRawResponse {
        val raw = post(
            AiProviderType.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta/interactions",
            mapOf("x-goog-api-key" to apiKey),
            buildRequestBody(model, request).toString(),
        )
        val response = JSONObject(raw)
        return AiRawResponse(
            provider = AiProviderType.GEMINI,
            model = model,
            jsonText = extractText(response) ?: throw AiTransportException.InvalidResponse(),
            usage = response.optJSONObject("usage")?.let { usage ->
                AiUsageMetadata(
                    inputTokens = usage.optLong("total_input_tokens").takeIf { usage.has("total_input_tokens") },
                    outputTokens = usage.optLong("total_output_tokens").takeIf { usage.has("total_output_tokens") },
                    totalTokens = usage.optLong("total_tokens").takeIf { usage.has("total_tokens") },
                    thoughtsTokens = usage.optLong("total_thought_tokens").takeIf { usage.has("total_thought_tokens") },
                    cachedTokens = usage.optLong("total_cached_tokens").takeIf { usage.has("total_cached_tokens") },
                )
            },
            finishReason = response.optString("status").takeIf { it.isNotBlank() },
        )
    }

    internal fun buildRequestBody(model: String, request: AiStructuredRequest): JSONObject {
        val responseFormat = JSONObject()
            .put("type", "text")
            .put("mime_type", "application/json")
        if (request.useNativeSchema) {
            responseFormat.put("schema", JSONObject(request.schemaJson))
        }

        val body = JSONObject()
            .put("model", model)
            .put("input", buildInput(request))
            .put("system_instruction", request.systemPrompt)
            .put("store", false)
            .put("response_format", responseFormat)

        val generationConfig = JSONObject()
        request.maxOutputTokens?.let { generationConfig.put("max_output_tokens", it) }
        if (generationConfig.length() > 0) body.put("generation_config", generationConfig)
        return body
    }

    private fun buildInput(request: AiStructuredRequest): Any {
        if (request.image == null) return request.userPrompt
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", request.userPrompt))
            .put(
                JSONObject()
                    .put("type", "image")
                    .put("data", request.image.base64Data)
                    .put("mime_type", request.image.mimeType),
            )
    }

    private fun extractText(root: JSONObject): String? {
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val steps = root.optJSONArray("steps") ?: return null
        val text = buildString {
            for (index in 0 until steps.length()) {
                val step = steps.optJSONObject(index) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val block = content.optJSONObject(contentIndex) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text"))
                }
            }
        }
        return text.takeIf { it.isNotBlank() }
    }
}
