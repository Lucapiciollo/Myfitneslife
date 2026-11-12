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

    private val protocolBySchema = mapOf(
        "myfitai_bia_raw_pipe_v2" to "B2",
        "myfitai_bia_pipe_v1" to "BIA1",
        "myfitai_bia_progress_coach_pipe_v2" to "BA2",
        "myfitai_progress_analysis_pipe_v1" to "PA1",
        "myfitai_body_proportion_pipe_v1" to "BP1",
        "myfitai_weekly_review_pipe_v1" to "WR1",
        "myfitai_meal_alternatives_pipe_v1" to "MA1",
        "myfitai_nutrition_advice_pipe_v1" to "NA1",
        "myfitai_cheat_understanding_pipe_v1" to "CU1",
        "myfitai_cheat_adjustment_pipe_v1" to "CA1",
        "myfitai_plan_review_pipe_v1" to "PR1",
        "myfitai_nutrition_path_pipe_v1" to "NP1",
        "myfitai_weekly_nutrition_pipe_v1" to "MFP1",
    )

    private fun retryPrompt(schemaName: String, firstResponse: String, validator: (String) -> Result<Unit>): String = runBlocking {
        val provider = object : AiProvider {
            override val type = AiProviderType.OPENAI
            var calls = 0
            var retryPrompt = ""
            override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
                calls++
                if (calls == 2) retryPrompt = request.userPrompt
                return AiRawResponse(type, "fake", if (calls == 1) firstResponse else "{\"data\":\"ok\"}")
            }
        }
        AiExecutionService().execute(
            provider,
            AiStructuredRequest("system", "user", schemaName, AiCompactEnvelope.schemaJson("test")),
            maxSchemaRetries = 1,
            businessValidator = validator,
        )
        provider.retryPrompt
    }

    @Test
    fun businessRetryNamesTheExactProtocolForEveryCompactSchema() {
        protocolBySchema.forEach { (schema, header) ->
            val prompt = retryPrompt(schema, "{\"data\":\"bad\"}") { v ->
                if (v.contains("bad")) Result.failure(IllegalArgumentException("INVALID")) else Result.success(Unit)
            }
            assertTrue("$schema must request $header, got: $prompt", prompt.contains("must begin with $header "))
        }
    }

    @Test
    fun schemaRetryNamesTheExactProtocolForEveryCompactSchema() {
        protocolBySchema.forEach { (schema, header) ->
            val prompt = retryPrompt(schema, "{\"data\":\"x\",\"extra\":1}") { Result.success(Unit) }
            assertTrue("$schema must request $header, got: $prompt", prompt.contains("must begin with $header "))
        }
    }

    @Test
    fun unknownCompactSchemaFallsBackToGenericRetryRule() {
        val prompt = retryPrompt("myfitai_unknown_pipe_v1", "{\"data\":\"x\",\"extra\":1}") { Result.success(Unit) }
        assertTrue(prompt.contains("must follow its requested compact protocol"))
        assertTrue(!prompt.contains("must begin with"))
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
