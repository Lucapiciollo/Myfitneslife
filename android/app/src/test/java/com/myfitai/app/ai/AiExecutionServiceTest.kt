package com.myfitai.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExecutionServiceTest {

    private val schema = """
        {
          "type":"object",
          "additionalProperties":false,
          "properties":{
            "status":{"type":"string"},
            "value":{"type":"integer"}
          },
          "required":["status","value"]
        }
    """.trimIndent()

    @Test
    fun schemaValidator_rejectsMissingAndUnknownFields() {
        val result = CanonicalJsonSchemaValidator.validate("{\"status\":\"ok\",\"extra\":1}", schema)
        assertTrue(!result.valid)
        assertTrue(result.errors.any { it.contains("value is required") })
        assertTrue(result.errors.any { it.contains("extra is not allowed") })
    }

    @Test
    fun execute_retriesOnceAfterInvalidSchema() {
        runBlocking {
            val provider = FakeProvider(
                listOf(
                    "{\"status\":\"bad\"}",
                    "{\"status\":\"ok\",\"value\":2}"
                )
            )
            val service = AiExecutionService()
            val result = service.execute(
                provider = provider,
                request = AiStructuredRequest("system", "user", "test_schema", schema),
                maxSchemaRetries = 1,
            )
            assertEquals(2, provider.calls)
            assertEquals("{\"status\":\"ok\",\"value\":2}", result.jsonText)
        }
    }

    @Test
    fun compactSchemaRetry_requiresOnlyDataEnvelopeProperty() {
        runBlocking {
            val provider = CompactRetryProvider()
            AiExecutionService().execute(
                provider = provider,
                request = AiStructuredRequest(
                    systemPrompt = "system",
                    userPrompt = "user",
                    schemaName = "test_pipe_schema",
                    schemaJson = """
                        {"type":"object","additionalProperties":false,"properties":{"data":{"type":"string"}},"required":["data"]}
                    """.trimIndent(),
                ),
                maxSchemaRetries = 1,
            )
            assertTrue(provider.secondPrompt.contains("exactly one property named data"))
            assertTrue(provider.secondPrompt.contains("Do not include schema, status, metadata"))
        }
    }

    @Test
    fun compactBusinessRetryNamesTheProtocolForTheRequestedSchema() {
        runBlocking {
            val provider = object : AiProvider {
                override val type = AiProviderType.OPENAI
                var calls = 0
                var retryPrompt = ""
                override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
                    calls++
                    if (calls == 2) retryPrompt = request.userPrompt
                    val data = if (calls == 1) "{\"data\":\"bad\"}" else "{\"data\":\"ok\"}"
                    return AiRawResponse(type, "fake", data)
                }
            }
            AiExecutionService().execute(
                provider,
                AiStructuredRequest("system", "user", "myfitai_meal_alternatives_pipe_v1", AiCompactEnvelope.schemaJson("MA1 test")),
                maxSchemaRetries = 1,
                businessValidator = { value -> if (value.contains("bad")) Result.failure(IllegalArgumentException("MA1_INVALID")) else Result.success(Unit) },
            )
            assertTrue("retry prompt must request MA1, got: ${provider.retryPrompt}", provider.retryPrompt.contains("MA1"))
        }
    }

    @Test(expected = AiExecutionService.Failure.BusinessRejected::class)
    fun execute_doesNotBypassBusinessValidation() {
        runBlocking {
            AiExecutionService().execute(
                provider = FakeProvider(listOf("{\"status\":\"ok\",\"value\":2}")),
                request = AiStructuredRequest("system", "user", "test_schema", schema),
                businessValidator = { Result.failure(IllegalArgumentException("OUT_OF_TOLERANCE")) },
            )
        }
    }

    @Test(expected = AiExecutionService.Failure.OutputTruncated::class)
    fun execute_doesNotTreatTokenLimitAsSchemaError() {
        runBlocking {
            val provider = object : AiProvider {
                override val type = AiProviderType.GEMINI
                override suspend fun generateStructured(request: AiStructuredRequest) =
                    AiRawResponse(type, "fake", "{", finishReason = "MAX_TOKENS")
            }
            AiExecutionService().execute(
                provider = provider,
                request = AiStructuredRequest("system", "user", "test_schema", schema),
                maxSchemaRetries = 2,
            )
        }
    }

    private class FakeProvider(private val responses: List<String>) : AiProvider {
        override val type = AiProviderType.OPENAI
        var calls = 0
        override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
            val response = responses.getOrElse(calls) { responses.last() }
            calls++
            return AiRawResponse(type, "fake", response)
        }
    }

    private class CompactRetryProvider : AiProvider {
        override val type = AiProviderType.OPENAI
        var secondPrompt = ""
        private var calls = 0

        override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
            calls++
            if (calls == 2) secondPrompt = request.userPrompt
            return if (calls == 1) {
                AiRawResponse(type, "fake", "{\"data\":\"x\",\"schema\":\"unexpected\"}")
            } else {
                AiRawResponse(type, "fake", "{\"data\":\"x\"}")
            }
        }
    }
}
