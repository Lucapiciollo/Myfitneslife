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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExecutionServiceTest {
    /** Test-only contracts for the generic mechanics; real schemas are covered by the registry test. */
    private val testSpecs = listOf(
        AiContractRegistry.Spec("test_schema", AiContractRegistry.Format.NATIVE_JSON, null, null, false),
        AiContractRegistry.Spec("test_pipe_schema", AiContractRegistry.Format.COMPACT_PIPE, "TP1", null, false),
    ).associateBy { it.schemaName }
    private val events = mutableListOf<AiDiagnosticEvent>()

    private fun service() = AiExecutionService(
        contracts = { name -> testSpecs[name] ?: AiContractRegistry.find(name) },
        diagnostics = { events += it },
    )

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
            val result = service().execute(
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
            service().execute(
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
            service().execute(
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
        service().execute(
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
    fun unregisteredSchemaFailsBeforeAnyProviderCallWithNoSilentFallback() {
        val provider = FakeProvider(listOf("{\"data\":\"ok\"}"))
        val error = runCatching {
            runBlocking {
                service().execute(provider, AiStructuredRequest("s", "u", "myfitai_unknown_pipe_v1", AiCompactEnvelope.schemaJson("t")))
            }
        }.exceptionOrNull()
        assertTrue("got $error", error is AiExecutionService.Failure.ContractNotRegistered)
        assertEquals(0, provider.calls)
        assertEquals(listOf("CONTRACT_NOT_REGISTERED"), events.map { it.outcome })
    }

    @Test
    fun substringOfARegisteredNameIsNotARegisteredSchema() {
        val provider = FakeProvider(listOf("{\"data\":\"ok\"}"))
        val error = runCatching {
            runBlocking {
                service().execute(provider, AiStructuredRequest("s", "u", "x_myfitai_bia_pipe_v1_y", AiCompactEnvelope.schemaJson("t")))
            }
        }.exceptionOrNull()
        assertTrue("got $error", error is AiExecutionService.Failure.ContractNotRegistered)
        assertEquals(0, provider.calls)
    }

    private fun failureOfResponse(raw: AiRawResponse): Throwable? {
        val provider = object : AiProvider {
            override val type = AiProviderType.GEMINI
            override suspend fun generateStructured(request: AiStructuredRequest) = raw
        }
        return runCatching {
            runBlocking { service().execute(provider, AiStructuredRequest("s", "u", "test_schema", schema), maxSchemaRetries = 2) }
        }.exceptionOrNull()
    }

    @Test
    fun finishReasonIsClassifiedAndNeverASuccessWhenAbnormal() {
        val json = "{\"status\":\"ok\",\"value\":2}"
        fun raw(reason: String?) = AiRawResponse(AiProviderType.GEMINI, "fake", json, finishReason = reason)
        assertTrue(failureOfResponse(raw("MAX_TOKENS")) is AiExecutionService.Failure.OutputTruncated)
        assertTrue(failureOfResponse(raw("length")) is AiExecutionService.Failure.OutputTruncated)
        assertTrue(failureOfResponse(raw("max_output_tokens")) is AiExecutionService.Failure.OutputTruncated)
        assertTrue(failureOfResponse(raw("SAFETY")) is AiExecutionService.Failure.Refused)
        assertTrue(failureOfResponse(raw("content_filter")) is AiExecutionService.Failure.Refused)
        assertTrue(failureOfResponse(raw("SOMETHING_NEW")) is AiTransportException.InvalidResponse)
        assertNull(failureOfResponse(raw("STOP")))
        assertNull(failureOfResponse(raw(null)))
        assertNull(failureOfResponse(raw("completed")))
    }

    @Test
    fun blankOutputIsEmptyOutputNotASchemaRetry() {
        val provider = FakeProvider(listOf("   "))
        val error = runCatching {
            runBlocking { service().execute(provider, AiStructuredRequest("s", "u", "test_schema", schema), maxSchemaRetries = 2) }
        }.exceptionOrNull()
        assertTrue("got $error", error is AiExecutionService.Failure.EmptyOutput)
        assertEquals(1, provider.calls)
    }

    @Test
    fun unparseableJsonIsRetriedThenReportedAsMalformedJson() {
        val provider = FakeProvider(listOf("not json at all"))
        val error = runCatching {
            runBlocking { service().execute(provider, AiStructuredRequest("s", "u", "test_schema", schema), maxSchemaRetries = 1) }
        }.exceptionOrNull()
        assertTrue("got $error", error is AiExecutionService.Failure.MalformedJson)
        assertEquals(2, provider.calls)
    }

    @Test
    fun validJsonWithWrongShapeIsStillInvalidSchema() {
        val provider = FakeProvider(listOf("{\"status\":\"bad\"}"))
        val error = runCatching {
            runBlocking { service().execute(provider, AiStructuredRequest("s", "u", "test_schema", schema), maxSchemaRetries = 0) }
        }.exceptionOrNull()
        assertTrue("got $error", error is AiExecutionService.Failure.InvalidSchema)
    }

    @Test
    fun cancellationIsAlwaysRethrownUntouched() {
        val cancelled = kotlinx.coroutines.CancellationException("stop")
        val provider = object : AiProvider {
            override val type = AiProviderType.OPENAI
            override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse = throw cancelled
        }
        val error = runCatching {
            runBlocking { service().execute(provider, AiStructuredRequest("s", "u", "test_schema", schema)) }
        }.exceptionOrNull()
        assertTrue(error === cancelled)
        assertEquals(listOf("CANCELLED"), events.map { it.outcome })
    }

    @Test
    fun diagnosticsCarryOnlySchemaOutcomeAttemptAndDuration() {
        val marker = "ZZ-MODEL-TEXT-ZZ"
        val provider = FakeProvider(listOf("{\"status\":\"$marker\"}", "{\"status\":\"$marker\",\"value\":1}"))
        runBlocking { service().execute(provider, AiStructuredRequest("s", "u", "test_schema", schema), maxSchemaRetries = 1) }
        val failing = FakeProvider(listOf("{\"status\":\"$marker\"}"))
        runCatching {
            runBlocking { service().execute(failing, AiStructuredRequest("s", "u", "test_schema", schema), maxSchemaRetries = 1) }
        }
        assertEquals(listOf("OK", "INVALID_SCHEMA"), events.map { it.outcome })
        assertEquals(listOf(1, 1), events.map { it.attempt })
        events.forEach {
            assertEquals("test_schema", it.schemaName)
            assertTrue(it.durationMs >= 0)
            assertTrue(!it.toString().contains(marker))
            assertTrue(!AiDiagnostics.format(it).contains(marker))
        }
    }

    @Test(expected = AiExecutionService.Failure.BusinessRejected::class)
    fun execute_doesNotBypassBusinessValidation() {
        runBlocking {
            service().execute(
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
            service().execute(
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
                service().execute(provider, AiStructuredRequest("s", "u", "test_schema", schema), maxSchemaRetries = 2)
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
