package com.myfitai.app.ai

import com.myfitai.app.domain.body.BiaAnalysisContract
import com.myfitai.app.domain.body.BiaImportCompactContract
import com.myfitai.app.domain.body.BiaRawImportContract
import com.myfitai.app.domain.body.BodyProportionAnalysisService
import com.myfitai.app.domain.food.CheatAdjustmentCompactContract
import com.myfitai.app.domain.food.CheatAdjustmentContract
import com.myfitai.app.domain.food.CheatUnderstandingCompactContract
import com.myfitai.app.domain.food.CheatUnderstandingContract
import com.myfitai.app.domain.food.MealAlternativeCompactContract
import com.myfitai.app.domain.food.MealAlternativeContract
import com.myfitai.app.domain.food.NutritionAdviceCompactContract
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionPathContract
import com.myfitai.app.domain.food.NutritionPlanCompactContract
import com.myfitai.app.domain.food.PlanReviewCompactContract
import com.myfitai.app.domain.progress.ProgressAnalysisCompactContract
import com.myfitai.app.domain.review.WeeklyReviewCompactContract
import com.myfitai.app.domain.review.WeeklyReviewContract
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

    /** Every compact schema declared by a contract, with the protocol text its header comes from (no copy of the table). */
    private val contractProtocols: List<Pair<String, String>> = listOf(
        BiaRawImportContract.SCHEMA_NAME to BiaRawImportContract.PROTOCOL,
        BiaImportCompactContract.SCHEMA_NAME to BiaImportCompactContract.PROTOCOL,
        BiaAnalysisContract.SCHEMA_NAME to "BA2",
        BodyProportionAnalysisService.SCHEMA_NAME to "BP1",
        ProgressAnalysisCompactContract.SCHEMA_NAME to ProgressAnalysisCompactContract.PROTOCOL,
        WeeklyReviewCompactContract.SCHEMA_NAME to WeeklyReviewCompactContract.PROTOCOL,
        MealAlternativeCompactContract.SCHEMA_NAME to MealAlternativeCompactContract.PROTOCOL,
        NutritionAdviceCompactContract.SCHEMA_NAME to NutritionAdviceCompactContract.PROTOCOL,
        CheatUnderstandingCompactContract.SCHEMA_NAME to CheatUnderstandingCompactContract.PROTOCOL,
        CheatAdjustmentCompactContract.SCHEMA_NAME to CheatAdjustmentCompactContract.PROTOCOL,
        PlanReviewCompactContract.SCHEMA_NAME to PlanReviewCompactContract.PROTOCOL,
        NutritionPathContract.SCHEMA_NAME to NutritionPathContract.PROTOCOL,
        NutritionPlanCompactContract.SCHEMA_NAME to NutritionPlanCompactContract.PROTOCOL,
    ).map { (schema, protocol) -> schema to protocol.lineSequence().first().trim() }

    @Test
    fun protocolTable_matchesTheHeaderDeclaredByEveryContract() {
        contractProtocols.forEach { (schema, header) ->
            assertEquals(schema, header, AiExecutionService.COMPACT_PROTOCOLS[schema])
        }
        // No missing and no stale entries.
        assertEquals(contractProtocols.map { it.first }.toSet(), AiExecutionService.COMPACT_PROTOCOLS.keys)
    }

    @Test
    fun everyDeclaredPipeSchemaHasAProtocolEntry() {
        listOf(
            CheatAdjustmentContract.SCHEMA_NAME, CheatUnderstandingContract.SCHEMA_NAME, MealAlternativeContract.SCHEMA_NAME,
            NutritionAdviceContract.SCHEMA_NAME, WeeklyReviewContract.SCHEMA_NAME,
        ).forEach { schema -> assertTrue("$schema missing from protocol table", schema in AiExecutionService.COMPACT_PROTOCOLS) }
    }
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
        contractProtocols.forEach { (schema, header) ->
            val prompt = retryPrompt(schema, "{\"data\":\"bad\"}") { v ->
                if (v.contains("bad")) Result.failure(IllegalArgumentException("INVALID")) else Result.success(Unit)
            }
            assertTrue("$schema must request $header, got: $prompt", prompt.contains("must begin with $header "))
        }
    }

    @Test
    fun schemaRetryNamesTheExactProtocolForEveryCompactSchema() {
        contractProtocols.forEach { (schema, header) ->
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

    private class ScriptedProvider(private val script: List<() -> String>) : AiProvider {
        override val type = AiProviderType.OPENAI
        var calls = 0
        override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
            val step = script[calls++]
            return AiRawResponse(type, "fake", step())
        }
    }

    private fun runScripted(vararg script: () -> String): Pair<ScriptedProvider, Throwable?> {
        val provider = ScriptedProvider(script.toList())
        val error = runCatching {
            runBlocking {
                AiExecutionService().execute(provider, AiStructuredRequest("s", "u", "test_schema", schema), maxSchemaRetries = 2)
            }
        }.exceptionOrNull()
        return provider to error
    }

    @Test
    fun transientErrorBeforeAnyResponse_isRethrownSoTheJobMayRetry() {
        val network = AiTransportException.Network()
        val (provider, error) = runScripted({ throw network })
        assertTrue(error === network)
        assertEquals(1, provider.calls)
    }

    @Test
    fun transientErrorAfterAPaidSchemaRetry_isNotRetriedByTheJob() {
        val (provider, error) = runScripted(
            { "{\"status\":\"bad\"}" },
            { throw AiTransportException.Http(AiProviderType.OPENAI, 503, AiTransportFailureKind.PROVIDER_UNAVAILABLE) },
        )
        assertTrue("got $error", error is AiExecutionService.Failure.PaidRetryBudgetConsumed)
        assertEquals("TRANSIENT_AFTER_PAID_RETRY", error?.message)
        assertEquals(2, provider.calls)
    }

    @Test
    fun permanentTransportErrorAfterARetry_keepsItsOwnType() {
        val quota = AiTransportException.Http(AiProviderType.OPENAI, 429, AiTransportFailureKind.QUOTA_EXHAUSTED)
        val (_, error) = runScripted({ "{\"status\":\"bad\"}" }, { throw quota })
        assertTrue(error === quota)
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
