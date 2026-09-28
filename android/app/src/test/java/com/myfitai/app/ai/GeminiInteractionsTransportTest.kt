package com.myfitai.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiInteractionsTransportTest {
    @Test
    fun requestAndResponse_useCanonicalInteractionsShape() = runBlocking {
        var requestBody: JSONObject? = null
        val transport = GeminiInteractionsTransport { _, _, _, body ->
            requestBody = JSONObject(body)
            """
                {
                  "status": "completed",
                  "output_text": "{\"status\":\"ok\"}",
                  "usage": {
                    "total_input_tokens": 11,
                    "total_output_tokens": 7,
                    "total_tokens": 18
                  }
                }
            """.trimIndent()
        }

        val response = transport.generate(
            apiKey = "test-key",
            model = "gemini-3.5-flash-lite",
            request = AiStructuredRequest(
                systemPrompt = "Return JSON",
                userPrompt = "Reply with status ok",
                schemaName = "status",
                schemaJson = """{"type":"object","properties":{"status":{"type":"string"}}}""",
                maxOutputTokens = 64,
            ),
        )

        val body = requireNotNull(requestBody)
        assertEquals("gemini-3.5-flash-lite", body.getString("model"))
        assertEquals("Reply with status ok", body.getString("input"))
        assertEquals(false, body.getBoolean("store"))
        assertEquals("application/json", body.getJSONObject("response_format").getString("mime_type"))
        assertEquals(64, body.getJSONObject("generation_config").getInt("max_output_tokens"))
        assertEquals("{\"status\":\"ok\"}", response.jsonText)
        assertEquals(11L, response.usage?.inputTokens)
        assertEquals(18L, response.usage?.totalTokens)
    }

    @Test
    fun imageInput_usesInteractionsImageContentBlock() = runBlocking {
        var requestBody: JSONObject? = null
        val transport = GeminiInteractionsTransport { _, _, _, body ->
            requestBody = JSONObject(body)
            """{"steps":[{"type":"model_output","content":[{"type":"text","text":"{}"}]}]}"""
        }

        transport.generate(
            apiKey = "test-key",
            model = "gemini-3.5-flash-lite",
            request = AiStructuredRequest(
                systemPrompt = "Return JSON",
                userPrompt = "Read this",
                schemaName = "image",
                schemaJson = """{"type":"object"}""",
                image = AiImageInput("image/jpeg", "ZmFrZQ=="),
            ),
        )

        val input = requireNotNull(requestBody).getJSONArray("input")
        assertEquals("text", input.getJSONObject(0).getString("type"))
        assertEquals("image", input.getJSONObject(1).getString("type"))
        assertEquals("image/jpeg", input.getJSONObject(1).getString("mime_type"))
        assertTrue(input.getJSONObject(1).has("data"))
    }
}
