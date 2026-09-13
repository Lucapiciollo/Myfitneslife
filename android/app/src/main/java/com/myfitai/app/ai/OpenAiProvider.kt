package com.myfitai.app.ai

import com.myfitai.app.security.SecureOpenAiKeyStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Responses API transport. The API key is decrypted only for the duration
 * of generateStructured() and is never stored on this provider or logged.
 */
class OpenAiProvider(
    private val keyStore: SecureOpenAiKeyStore,
    private val model: String = DEFAULT_MODEL,
) : AiProvider {
    override val type: AiProviderType = AiProviderType.OPENAI

    override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
        val apiKey = keyStore.load()?.takeIf { it.isNotBlank() }
            ?: throw AiTransportException.NotConfigured(type)

        val schema = JSONObject(request.schemaJson)
        val body = JSONObject()
            .put("model", model)
            .put("instructions", request.systemPrompt)
            .put("input", request.userPrompt)
            .put("store", false)
            .put("max_output_tokens", request.maxOutputTokens)
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

        val raw = HttpJsonClient.post(
            url = ENDPOINT,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = body.toString(),
        )
        val jsonText = extractOutputText(JSONObject(raw))
            ?: throw AiTransportException.InvalidResponse()
        return AiRawResponse(type, model, jsonText)
    }

    private fun extractOutputText(root: JSONObject): String? {
        val output = root.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    return part.optString("text").takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/responses"
        const val DEFAULT_MODEL = "gpt-5.6-luna"
    }
}
