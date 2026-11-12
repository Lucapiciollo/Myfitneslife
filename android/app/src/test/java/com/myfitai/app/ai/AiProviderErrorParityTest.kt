package com.myfitai.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Builders of provider response bodies (no network, no real data) shared by the parity tests. */
private object ProviderBodies {
    const val TEXT = "{\"data\":\"ok\"}"

    fun gemini(text: String? = TEXT, finishReason: String? = "STOP", blockReason: String? = null, thought: Boolean = false): String {
        val part = JSONObject().put("text", text.orEmpty()).apply { if (thought) put("thought", true) }
        val candidate = JSONObject().put("content", JSONObject().put("parts", JSONArray().put(part)))
        finishReason?.let { candidate.put("finishReason", it) }
        val root = JSONObject().put("candidates", JSONArray().put(candidate))
        blockReason?.let { root.put("promptFeedback", JSONObject().put("blockReason", it)) }
        return root.toString()
    }

    fun openAi(
        text: String? = TEXT,
        status: String = "completed",
        incompleteReason: String? = null,
        refusal: Boolean = false,
        reasoningOnly: Boolean = false,
    ): String {
        val content = JSONArray()
        if (refusal) content.put(JSONObject().put("type", "refusal").put("refusal", "redacted"))
        else if (text != null) content.put(JSONObject().put("type", "output_text").put("text", text))
        val output = JSONArray()
        output.put(if (reasoningOnly) JSONObject().put("type", "reasoning") else JSONObject().put("type", "message").put("content", content))
        val root = JSONObject().put("status", status).put("output", output)
        incompleteReason?.let { root.put("incomplete_details", JSONObject().put("reason", it)) }
        return root.toString()
    }

    fun parseGemini(body: String): AiRawResponse = GeminiByokProvider.parseGenerateContent(body, "gemini-test")
    fun parseOpenAi(body: String): AiRawResponse = OpenAiProvider.parseResponse(body, "openai-test")

    /** The exception the shared HTTP client throws for a non-2xx answer (status + body go through the same classifier). */
    fun http(provider: AiProviderType, status: Int, body: String): Nothing =
        throw AiTransportException.Http(provider, status, AiTransportFailureClassifier.classify(status, body))
}

/**
 * Parity: the same situation, translated by the pure Gemini and OpenAI parsers (and by the shared HTTP
 * classifier), must end in the SAME [AiFailure] category for both providers.
 */
@RunWith(Parameterized::class)
class AiProviderErrorParityTest(
    @Suppress("unused") private val name: String,
    private val expected: String,
    private val gemini: () -> Any,
    private val openAi: () -> Any,
) {
    companion object {
        private fun scenario(name: String, expected: String, gemini: () -> Any, openAi: () -> Any): Array<Any> =
            arrayOf(name, expected, gemini, openAi)

        private fun httpScenario(name: String, expected: String, status: Int, geminiBody: String, openAiBody: String) = scenario(
            name, expected,
            { ProviderBodies.http(AiProviderType.GEMINI, status, geminiBody) },
            { ProviderBodies.http(AiProviderType.OPENAI, status, openAiBody) },
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): Collection<Array<Any>> = listOf(
            scenario("stop", "OK",
                { ProviderBodies.parseGemini(ProviderBodies.gemini()) },
                { ProviderBodies.parseOpenAi(ProviderBodies.openAi()) }),
            scenario("max_tokens_with_partial_text", "OUTPUT_TRUNCATED",
                { ProviderBodies.parseGemini(ProviderBodies.gemini(text = "{\"data\":\"par", finishReason = "MAX_TOKENS")) },
                { ProviderBodies.parseOpenAi(ProviderBodies.openAi(text = "{\"data\":\"par", status = "incomplete", incompleteReason = "max_output_tokens")) }),
            scenario("safety_or_refusal", "REFUSED",
                { ProviderBodies.parseGemini(ProviderBodies.gemini(text = "", finishReason = "SAFETY")) },
                { ProviderBodies.parseOpenAi(ProviderBodies.openAi(text = null, refusal = true)) }),
            scenario("prompt_or_content_blocked", "REFUSED",
                { ProviderBodies.parseGemini(ProviderBodies.gemini(text = "", finishReason = null, blockReason = "PROHIBITED_CONTENT")) },
                { ProviderBodies.parseOpenAi(ProviderBodies.openAi(text = null, status = "incomplete", incompleteReason = "content_filter")) }),
            scenario("empty_text", "EMPTY_OUTPUT",
                { ProviderBodies.parseGemini(ProviderBodies.gemini(text = "")) },
                { ProviderBodies.parseOpenAi(ProviderBodies.openAi(text = "")) }),
            scenario("reasoning_only_no_answer", "EMPTY_OUTPUT",
                { ProviderBodies.parseGemini(ProviderBodies.gemini(text = "pensiero", thought = true)) },
                { ProviderBodies.parseOpenAi(ProviderBodies.openAi(reasoningOnly = true)) }),
            scenario("unknown_finish_or_status", "PROVIDER_PROTOCOL",
                { ProviderBodies.parseGemini(ProviderBodies.gemini(finishReason = "SOMETHING_NEW")) },
                { ProviderBodies.parseOpenAi(ProviderBodies.openAi(status = "failed")) }),
            scenario("malformed_http_body", "PROVIDER_PROTOCOL",
                { ProviderBodies.parseGemini("<html>bad gateway") },
                { ProviderBodies.parseOpenAi("<html>bad gateway") }),
            httpScenario("http_429_quota", "QUOTA_EXHAUSTED", 429,
                "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota, check your plan and billing details.\",\"status\":\"RESOURCE_EXHAUSTED\"}}",
                "{\"error\":{\"message\":\"You exceeded your current quota, check your plan and billing details.\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}"),
            httpScenario("http_429_rate_limit", "RATE_LIMITED", 429,
                "{\"error\":{\"code\":429,\"message\":\"Too many requests, slow down.\"}}",
                "{\"error\":{\"message\":\"Rate limit reached for requests.\",\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}"),
            httpScenario("http_500", "PROVIDER_UNAVAILABLE", 500,
                "{\"error\":{\"code\":500,\"message\":\"Internal error encountered.\",\"status\":\"INTERNAL\"}}",
                "{\"error\":{\"message\":\"The server had an error while processing your request.\",\"type\":\"server_error\"}}"),
            httpScenario("http_503", "PROVIDER_UNAVAILABLE", 503,
                "{\"error\":{\"code\":503,\"message\":\"The service is currently overloaded.\",\"status\":\"UNAVAILABLE\"}}",
                "{\"error\":{\"message\":\"The engine is currently overloaded, try again later.\",\"type\":\"server_error\"}}"),
            httpScenario("http_401", "AUTH", 401,
                "{\"error\":{\"code\":401,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"UNAUTHENTICATED\"}}",
                "{\"error\":{\"message\":\"Incorrect API key provided.\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"),
            httpScenario("http_403_permission", "AUTH", 403,
                "{\"error\":{\"code\":403,\"message\":\"Permission denied for this key.\",\"status\":\"PERMISSION_DENIED\"}}",
                "{\"error\":{\"message\":\"You do not have permission to perform this action.\",\"type\":\"invalid_request_error\"}}"),
            httpScenario("http_404_model", "MODEL_UNAVAILABLE", 404,
                "{\"error\":{\"code\":404,\"message\":\"models/x is not found for API version v1beta.\",\"status\":\"NOT_FOUND\"}}",
                "{\"error\":{\"message\":\"The model does not exist or you do not have access to it.\",\"code\":\"model_not_found\"}}"),
            httpScenario("http_400_schema", "PROVIDER_PROTOCOL", 400,
                "{\"error\":{\"code\":400,\"message\":\"Invalid argument: response schema is not supported.\"}}",
                "{\"error\":{\"message\":\"Invalid schema for response_format.\",\"type\":\"invalid_request_error\"}}"),
            scenario("network", "NETWORK",
                { throw AiTransportException.Network() },
                { throw AiTransportException.Network() }),
        )
    }

    private fun failureOf(block: () -> Any): AiFailure? = try {
        block()
        null
    } catch (error: Throwable) {
        AiFailure.classify(error)
    }

    @Test
    fun bothProvidersMapTheSituationToTheExpectedCategory() {
        assertEquals("gemini", expected, failureOf(gemini)?.code ?: "OK")
        assertEquals("openai", expected, failureOf(openAi)?.code ?: "OK")
    }

    @Test
    fun bothProvidersAgreeOnRetryabilityAndUserMessage() {
        val g = failureOf(gemini)
        val o = failureOf(openAi)
        assertEquals(g == null, o == null)
        if (g != null && o != null) {
            assertEquals(g.code, o.code)
            assertEquals(g.retryable, o.retryable)
            assertEquals(g.userMessage, o.userMessage)
            assertEquals(g.surface, o.surface)
        }
    }
}

/** Parity of the text produced by a normal stop, and of what happens to unusable model text afterwards. */
class AiProviderTextParityTest {
    private val schema = AiCompactEnvelope.schemaJson

    private fun execute(raw: AiRawResponse): Result<AiExecutionService.ValidatedExecution<String>> = runCatching {
        val provider = object : AiProvider {
            override val type = raw.provider
            override suspend fun generateStructured(request: AiStructuredRequest) = raw
        }
        runBlocking {
            AiExecutionService(diagnostics = {}).executeValidated(
                provider,
                AiStructuredRequest("system", "user", "myfitai_nutrition_advice_pipe_v1", schema),
                maxSchemaRetries = 0,
            ) { json -> Result.success(json) }
        }
    }

    @Test
    fun normalStop_yieldsTheSameTextAndAValidatedOutputForBothProviders() {
        val gemini = ProviderBodies.parseGemini(ProviderBodies.gemini())
        val openAi = ProviderBodies.parseOpenAi(ProviderBodies.openAi())
        assertEquals(ProviderBodies.TEXT, gemini.jsonText)
        assertEquals(gemini.jsonText, openAi.jsonText)
        assertNotNull(execute(gemini).getOrNull())
        assertNotNull(execute(openAi).getOrNull())
    }

    @Test
    fun malformedModelText_isTheSameCategoryForBothProviders() {
        val text = "{\"data\":\"ok"
        val gemini = ProviderBodies.parseGemini(ProviderBodies.gemini(text = text))
        val openAi = ProviderBodies.parseOpenAi(ProviderBodies.openAi(text = text))
        assertEquals(AiFailure.MalformedJson, execute(gemini).exceptionOrNull()?.let { AiFailure.classify(it) })
        assertEquals(AiFailure.MalformedJson, execute(openAi).exceptionOrNull()?.let { AiFailure.classify(it) })
    }

    @Test
    fun schemaInvalidModelText_isTheSameCategoryForBothProviders() {
        val text = "{\"data\":123}"
        val gemini = ProviderBodies.parseGemini(ProviderBodies.gemini(text = text))
        val openAi = ProviderBodies.parseOpenAi(ProviderBodies.openAi(text = text))
        assertEquals(AiFailure.SchemaInvalid, execute(gemini).exceptionOrNull()?.let { AiFailure.classify(it) })
        assertEquals(AiFailure.SchemaInvalid, execute(openAi).exceptionOrNull()?.let { AiFailure.classify(it) })
    }

    @Test
    fun normalStop_isNotAFailure() {
        assertNull(execute(ProviderBodies.parseGemini(ProviderBodies.gemini())).exceptionOrNull())
        assertNull(execute(ProviderBodies.parseOpenAi(ProviderBodies.openAi())).exceptionOrNull())
    }
}
