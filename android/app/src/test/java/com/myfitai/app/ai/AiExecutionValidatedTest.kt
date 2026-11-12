package com.myfitai.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tranche C3: `executeValidated` returns a [ValidatedOutput] after canonical schema v2 + business validation,
 * without changing the legacy `execute` signatures. Failures stay inside the [AiFailure] taxonomy and never
 * carry model text (only paths/categories/stable rule codes).
 */
class AiExecutionValidatedTest {
    private val testSpec = AiContractRegistry.Spec("test_schema", AiContractRegistry.Format.NATIVE_JSON, null, null, false)
    private val events = mutableListOf<AiDiagnosticEvent>()
    private val service = AiExecutionService(
        contracts = { name -> if (name == testSpec.schemaName) testSpec else AiContractRegistry.find(name) },
        diagnostics = { events += it },
    )
    private val schema = """
        {"type":"object","additionalProperties":false,
         "properties":{"status":{"type":"string","enum":["ok","warn"]},"value":{"type":"integer","minimum":0}},
         "required":["status","value"]}
    """.trimIndent()
    private val request = AiStructuredRequest("system", "user", "test_schema", schema)

    private class ScriptedProvider(private val responses: List<AiRawResponse>) : AiProvider {
        override val type = AiProviderType.OPENAI
        var calls = 0
        override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse =
            responses[minOf(calls++, responses.lastIndex)]
    }

    private fun provider(vararg texts: String) =
        ScriptedProvider(texts.map { AiRawResponse(AiProviderType.OPENAI, "fake-model", it) })

    private fun parseValue(json: String): Result<Int> = runCatching { org.json.JSONObject(json).getInt("value") }

    @Test
    fun executeValidated_returnsValidatedOutputWithParsedValueAndResponseMetadata() = runBlocking {
        val result = service.executeValidated(provider("""{"status":"ok","value":7}"""), request, 1, ::parseValue)
        assertEquals(7, result.output.value)
        assertEquals("test_schema", result.output.schemaName)
        assertEquals("fake-model", result.response.model)
        assertEquals("""{"status":"ok","value":7}""", result.response.jsonText)
        assertEquals("OK", events.last().outcome)
    }

    @Test
    fun executeValidated_retriesOnSchemaErrorThenSucceeds() = runBlocking {
        val provider = provider("""{"status":"ok"}""", """{"status":"ok","value":3}""")
        val result = service.executeValidated(provider, request, 1, ::parseValue)
        assertEquals(2, provider.calls)
        assertEquals(3, result.output.value)
        assertEquals(1, events.last().attempt)
    }

    @Test
    fun executeValidated_schemaErrorAfterRetries_isSchemaInvalidWithOnlyPathsAndCategories() = runBlocking {
        val secret = "SECRET_MODEL_TEXT_42"
        val provider = provider("""{"status":"$secret","value":1}""")
        try {
            service.executeValidated(provider, request, 0, ::parseValue)
            fail("expected a schema failure")
        } catch (error: AiExecutionService.Failure.InvalidSchema) {
            assertEquals(AiFailure.SchemaInvalid, AiFailure.classify(error))
            val issue = error.issues.single()
            assertEquals("$.status", issue.path)
            assertEquals(CanonicalJsonSchemaValidator.Category.ENUM_MISMATCH, issue.category)
            assertFalse(error.errors.joinToString().contains(secret))
            assertFalse(error.message.orEmpty().contains(secret))
        }
    }

    @Test
    fun executeValidated_notJsonObject_isMalformedJson() = runBlocking {
        try {
            service.executeValidated(provider("this is {not json"), request, 0, ::parseValue)
            fail("expected a malformed json failure")
        } catch (error: AiExecutionService.Failure.MalformedJson) {
            assertEquals(AiFailure.MalformedJson, AiFailure.classify(error))
        }
    }

    @Test
    fun executeValidated_businessFailure_isBusinessRuleViolatedWithStableCode() = runBlocking {
        val provider = provider("""{"status":"ok","value":1}""")
        try {
            service.executeValidated<Int>(provider, request, 1) { Result.failure(IllegalArgumentException("INVALID_KCAL: 4999 secret")) }
            fail("expected a business failure")
        } catch (error: AiExecutionService.Failure.BusinessRejected) {
            // Non-compact contract: a business failure is final (no paid retry).
            assertEquals(1, provider.calls)
            assertEquals(AiFailure.BusinessRuleViolated("INVALID_KCAL"), AiFailure.classify(error))
        }
    }

    @Test
    fun executeValidated_businessValidatorThatThrows_isClassifiedNotPropagatedRaw() = runBlocking {
        try {
            service.executeValidated<Int>(provider("""{"status":"ok","value":1}"""), request, 1) { error("INVALID_MACROS") }
            fail("expected a business failure")
        } catch (error: AiExecutionService.Failure.BusinessRejected) {
            assertEquals(AiFailure.BusinessRuleViolated("INVALID_MACROS"), AiFailure.classify(error))
        }
    }

    @Test
    fun executeValidated_abnormalFinishReasonStillFailsBeforeValidation() = runBlocking {
        val truncated = ScriptedProvider(listOf(AiRawResponse(AiProviderType.GEMINI, "m", """{"status":"ok","value":1}""", finishReason = "MAX_TOKENS")))
        try {
            service.executeValidated(truncated, request, 1, ::parseValue)
            fail("expected truncation")
        } catch (error: AiExecutionService.Failure.OutputTruncated) {
            assertEquals(AiFailure.Truncated, AiFailure.classify(error))
        }
    }

    @Test
    fun legacyExecute_isUnchanged() = runBlocking {
        val response = service.execute(provider("""{"status":"warn","value":0}"""), request, 1) { Result.success(Unit) }
        assertEquals("""{"status":"warn","value":0}""", response.jsonText)
    }

    @Test
    fun gatewayDefaultExecuteValidated_buildsOnExistingExecuteWithoutBreakingImplementers() = runBlocking {
        // A gateway written before C3 implements only `execute`.
        val legacy = object : AiRuntimeGateway {
            override suspend fun execute(
                request: AiStructuredRequest,
                maxSchemaRetries: Int,
                businessValidator: (String) -> Result<Unit>,
            ): AiExecutionService.ValidatedResponse {
                val json = """{"status":"ok","value":9}"""
                businessValidator(json).getOrThrow()
                return AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "legacy", json)
            }
        }
        val result = legacy.executeValidated(request, 1, ::parseValue)
        assertEquals(9, result.output.value)
        assertEquals("legacy", result.response.model)
    }

    @Test
    fun gatewayDefaultExecuteValidated_mapsSchemaRejectionToFailure() = runBlocking {
        val legacy = object : AiRuntimeGateway {
            override suspend fun execute(
                request: AiStructuredRequest,
                maxSchemaRetries: Int,
                businessValidator: (String) -> Result<Unit>,
            ) = AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "legacy", """{"status":"nope","value":1}""")
        }
        try {
            legacy.executeValidated(request, 1, ::parseValue)
            fail("expected a schema failure")
        } catch (error: AiExecutionService.Failure.InvalidSchema) {
            assertEquals("$.status", error.issues.single().path)
        }
    }

    @Test
    fun cancellationInsideBusinessValidatorIsNotSwallowed() {
        val result = runCatching {
            ValidatedOutput.validate<Int>("""{"status":"ok","value":1}""", schema, "s") { throw kotlinx.coroutines.CancellationException("stop") }
        }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
