package com.myfitai.app.ai

import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Responses API transport. The API key is decrypted only for the duration
 * of generateStructured() and is never stored on this provider or logged.
 */
class OpenAiProvider(
    private val credentialStore: SecureAiCredentialStore,
    private val model: String = AiModelConfig.OPENAI,
) : AiProvider {
    override val type: AiProviderType = AiProviderType.OPENAI

    override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
        val apiKey = credentialStore.read(AiCredentialProvider.OPENAI)?.takeIf { it.isNotBlank() }
            ?: throw AiTransportException.NotConfigured(type)
        return generateWithKey(apiKey, request)
    }

    suspend fun verifyApiKey(apiKey: String): AiRawResponse {
        return try {
            generateWithKey(apiKey.trim(), AiStructuredRequest(
                systemPrompt = "Return only the string ok.",
                userPrompt = "Reply with ok.",
                schemaName = "myfitai_provider_verification",
                schemaJson = "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"ok\"]}},\"required\":[\"status\"],\"additionalProperties\":false}",
                maxOutputTokens = 64,
            ))
        } catch (_: AiExecutionService.Failure) {
            // The HTTP call succeeded but the probe answer was empty/blocked/cut: verification only needs a readable reply.
            throw AiTransportException.InvalidResponse()
        }
    }

    private suspend fun generateWithKey(apiKey: String, request: AiStructuredRequest): AiRawResponse {
        require(apiKey.isNotBlank()) { "OpenAI API key must not be blank" }
        val raw = HttpJsonClient.post(
            provider = type,
            url = ENDPOINT,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = buildRequestBody(model, request).toString(),
        )
        return parseResponse(raw, model)
    }

    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/responses"

        /** Responses API body. The output cap is `max_output_tokens`, the same budget Gemini now receives. */
        internal fun buildRequestBody(model: String, request: AiStructuredRequest): JSONObject {
            val schema = JSONObject(request.schemaJson)
            val body = JSONObject()
                .put("model", model)
                .put("instructions", request.systemPrompt)
                .put("input", buildInputFor(request))
                .put("store", false)
                .put(
                    "text",
                    JSONObject().put(
                        "format",
                        JSONObject()
                            .put("type", "json_schema")
                            .put("name", request.schemaName)
                            .put("strict", true)
                            .put("schema", schema)
                    )
                )
            // Omit an application cap for long/variable-length plans; provider defaults and
            // model hard limits still apply. Keep explicit limits for bounded short agents.
            request.maxOutputTokens?.let { body.put("max_output_tokens", it) }
            return body
        }

        /**
         * Pure parser of a Responses API body (no network). `incomplete` with max_output_tokens -> Truncated,
         * content_filter -> Refused, a `refusal` content part -> Refused (its text is never read), no output text ->
         * EmptyOutput, `failed`/unknown status -> protocol error. Provider text is never copied into an exception.
         */
        internal fun parseResponse(raw: String, model: String): AiRawResponse {
            val response = try {
                JSONObject(raw)
            } catch (_: org.json.JSONException) {
                throw AiTransportException.InvalidResponse()
            }
            when (response.optString("status")) {
                "", "completed" -> Unit
                "incomplete" -> {
                    val reason = response.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
                    throw (AiFailure.fromFinishReason(reason.ifBlank { "incomplete" }) ?: AiFailure.Truncated).toException()
                }
                else -> throw AiTransportException.InvalidResponse()
            }
            if (hasRefusal(response)) throw AiExecutionService.Failure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL)
            val jsonText = extractOutputText(response) ?: throw AiExecutionService.Failure.EmptyOutput()
            val usage = response.optJSONObject("usage")?.let { value ->
                val inputDetails = value.optJSONObject("input_tokens_details")
                val outputDetails = value.optJSONObject("output_tokens_details")
                AiUsageMetadata(
                    inputTokens = value.optLong("input_tokens").takeIf { value.has("input_tokens") },
                    outputTokens = value.optLong("output_tokens").takeIf { value.has("output_tokens") },
                    totalTokens = value.optLong("total_tokens").takeIf { value.has("total_tokens") },
                    thoughtsTokens = outputDetails?.takeIf { it.has("reasoning_tokens") }?.optLong("reasoning_tokens"),
                    cachedTokens = inputDetails?.takeIf { it.has("cached_tokens") }?.optLong("cached_tokens"),
                )
            }
            return AiRawResponse(AiProviderType.OPENAI, model, jsonText, usage)
        }

        private fun buildInputFor(request: AiStructuredRequest): Any {
            val image = request.image ?: return request.userPrompt
            val content = JSONArray()
                .put(JSONObject().put("type", "input_text").put("text", request.userPrompt))
                .put(JSONObject().put("type", "input_image").put("image_url", "data:${image.mimeType};base64,${image.base64Data}"))
            return JSONArray().put(JSONObject().put("role", "user").put("content", content))
        }

        private fun hasRefusal(root: JSONObject): Boolean {
            val output = root.optJSONArray("output") ?: return false
            for (i in 0 until output.length()) {
                val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    if (content.optJSONObject(j)?.optString("type") == "refusal") return true
                }
            }
            return false
        }

        private fun extractOutputText(root: JSONObject): String? {
            val output = root.optJSONArray("output") ?: return null
            // Do not drop subsequent output_text items: a long weekly plan may be split.
            val text = buildString {
                for (i in 0 until output.length()) {
                    val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") append(part.optString("text"))
                    }
                }
            }
            return text.takeIf { it.isNotBlank() }
        }
    }
}
