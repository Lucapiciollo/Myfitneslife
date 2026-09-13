package com.myfitai.app.ai

import com.myfitai.app.security.SecureGeminiKeyStore
import org.json.JSONArray
import org.json.JSONObject

class GeminiProvider(
    private val keyStore: SecureGeminiKeyStore,
    private val model: String = DEFAULT_MODEL,
) : AiProvider {
    override val type: AiProviderType = AiProviderType.GEMINI

    override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
        val apiKey = keyStore.load()?.takeIf { it.isNotBlank() }
            ?: throw AiTransportException.NotConfigured(type)

        val prompt = buildString {
            append(request.systemPrompt.trim())
            append("\n\n")
            append(request.userPrompt.trim())
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseJsonSchema", JSONObject(request.schemaJson))
                    .put("maxOutputTokens", request.maxOutputTokens)
            )

        val raw = HttpJsonClient.post(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            headers = mapOf("x-goog-api-key" to apiKey),
            body = body.toString(),
        )
        val root = JSONObject(raw)
        val text = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?: throw AiTransportException.InvalidResponse()
        return AiRawResponse(type, model, text)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
