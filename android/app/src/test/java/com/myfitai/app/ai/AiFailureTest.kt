package com.myfitai.app.ai

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFailureTest {
    private fun http(status: Int, kind: AiTransportFailureKind) = AiTransportException.Http(AiProviderType.GEMINI, status, kind)

    @Test
    fun transport_isClassifiedIntoTheClosedTaxonomy() {
        assertEquals(AiFailure.Network, AiFailure.classify(AiTransportException.Network()))
        assertEquals(AiFailure.NotConfigured, AiFailure.classify(AiTransportException.NotConfigured(AiProviderType.OPENAI)))
        assertEquals(AiFailure.ProviderProtocol, AiFailure.classify(AiTransportException.InvalidResponse()))
        assertEquals(AiFailure.RateLimited, AiFailure.classify(http(429, AiTransportFailureKind.RATE_LIMITED)))
        assertEquals(AiFailure.ProviderUnavailable, AiFailure.classify(http(503, AiTransportFailureKind.PROVIDER_UNAVAILABLE)))
        assertEquals(AiFailure.ProviderUnavailable, AiFailure.classify(http(500, AiTransportFailureKind.UNKNOWN)))
        assertEquals(AiFailure.Quota, AiFailure.classify(http(429, AiTransportFailureKind.QUOTA_EXHAUSTED)))
        assertEquals(AiFailure.Auth, AiFailure.classify(http(401, AiTransportFailureKind.INVALID_API_KEY)))
        assertEquals(AiFailure.Auth, AiFailure.classify(http(401, AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD)))
        assertEquals(AiFailure.ModelUnavailable, AiFailure.classify(http(404, AiTransportFailureKind.MODEL_UNAVAILABLE)))
        assertEquals(AiFailure.ModelUnavailable, AiFailure.classify(http(400, AiTransportFailureKind.MODEL_REQUIRES_INTERACTIONS)))
        assertEquals(AiFailure.ProviderProtocol, AiFailure.classify(http(400, AiTransportFailureKind.SCHEMA)))
        assertEquals(AiFailure.ProviderProtocol, AiFailure.classify(http(418, AiTransportFailureKind.UNKNOWN)))
    }

    @Test
    fun executionFailures_areClassified() {
        assertEquals(AiFailure.Truncated, AiFailure.classify(AiExecutionService.Failure.OutputTruncated()))
        assertEquals(AiFailure.SchemaInvalid, AiFailure.classify(AiExecutionService.Failure.InvalidSchema(listOf("x"))))
        assertEquals(AiFailure.EmptyOutput, AiFailure.classify(AiExecutionService.Failure.EmptyOutput()))
        assertEquals(AiFailure.MalformedJson, AiFailure.classify(AiExecutionService.Failure.MalformedJson()))
        assertEquals(AiFailure.ContractNotRegistered, AiFailure.classify(AiExecutionService.Failure.ContractNotRegistered("x")))
        assertEquals(
            AiFailure.Refused(AiFailure.RefusalReason.SAFETY),
            AiFailure.classify(AiExecutionService.Failure.Refused(AiFailure.RefusalReason.SAFETY)),
        )
        assertEquals(
            AiFailure.PaidRetryBudgetConsumed,
            AiFailure.classify(AiExecutionService.Failure.PaidRetryBudgetConsumed(AiTransportException.Network())),
        )
        assertEquals(AiFailure.Cancelled, AiFailure.classify(CancellationException("c")))
        assertEquals(AiFailure.Unexpected, AiFailure.classify(IllegalStateException("boom")))
    }

    @Test
    fun businessRules_keepOnlyStableCodes() {
        val marker = "ZZ-MODEL-TEXT-ZZ"
        val known = AiFailure.classify(AiExecutionService.Failure.BusinessRejected("BIA_CLASSIFICATION_INVALID: $marker"))
        assertEquals(AiFailure.BusinessRuleViolated("BIA_CLASSIFICATION_INVALID"), known)
        assertEquals(AiFailure.BusinessRuleViolated(null), AiFailure.classify(AiExecutionService.Failure.BusinessRejected(marker)))
        assertEquals(AiFailure.BusinessRuleViolated("EMPTY_ACTION"), AiFailure.classify(IllegalArgumentException("EMPTY_ACTION: $marker")))
        assertEquals("BIA_RELIABILITY_INVALID", AiFailure.stableBusinessCode("BIA_RELIABILITY_INVALID_HIGH$marker"))
        assertEquals("BA2_L_ARITY_3", AiFailure.stableBusinessCode("BA2_L_ARITY_3"))
        assertNull(AiFailure.stableBusinessCode("not a code $marker"))
        assertNull(AiFailure.stableBusinessCode(null))
    }

    @Test
    fun businessCodes_doNotLoseTheLegacyOnes() {
        listOf(
            "INVALID_SCHEMA", "OUTPUT_TRUNCATED", "TRANSIENT_AFTER_PAID_RETRY", "BUSINESS_VALIDATION_FAILED",
            "BA2_V_INVALID", "BIA_RAW_HEADER_INVALID", "CA1_INVALID", "NA1_INVALID", "MA1_INVALID", "CU1_INVALID",
            "PROFILE_CONSTRAINTS_UNREADABLE", "INSUFFICIENT_DATA_MAX_TWO_ACTIONS", "UNSUPPORTED_MEDICAL_OR_CAUSAL_CLAIM",
        ).forEach { assertTrue(it, it in AiFailure.BUSINESS_CODES) }
        assertTrue(AiFailure.BUSINESS_CODES.size >= 150)
    }

    @Test
    fun retryable_isTrueOnlyForTransientCategories() {
        val transient = setOf(AiFailure.Network, AiFailure.RateLimited, AiFailure.ProviderUnavailable)
        AiFailure.ALL_STATIC.forEach { failure ->
            assertEquals(failure.code, failure in transient, failure.retryable)
        }
        assertFalse(AiFailure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL).retryable)
        assertFalse(AiFailure.BusinessRuleViolated("X").retryable)
        assertFalse(AiFailure.Cancelled.retryable)
    }

    @Test
    fun codesAreUniqueAndMessagesComeFromAClosedTable() {
        val all = AiFailure.ALL_STATIC + AiFailure.Refused(AiFailure.RefusalReason.SAFETY) + AiFailure.BusinessRuleViolated(null)
        assertEquals("duplicate codes", all.size, all.map { it.code }.toSet().size)
        all.forEach {
            assertTrue(it.code, it.code.matches(Regex("[A-Z_]+")))
            assertTrue(it.code, it.userMessage.isNotBlank())
            assertFalse(it.code, it.userMessage.any { c -> c in "{}|\n" })
        }
        // Reason of a refusal must never change the (static) text shown to the user.
        assertEquals(
            AiFailure.Refused(AiFailure.RefusalReason.SAFETY).userMessage,
            AiFailure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL).userMessage,
        )
    }

    @Test
    fun finishReason_isNeverASuccessUnlessItIsANormalStop() {
        listOf(null, "", "STOP", "stop", "FINISH_REASON_UNSPECIFIED", "completed", "end_turn").forEach {
            assertNull("$it", AiFailure.fromFinishReason(it))
        }
        listOf("MAX_TOKENS", "max_output_tokens", "length", "max_tokens", "incomplete").forEach {
            assertEquals(it, AiFailure.Truncated, AiFailure.fromFinishReason(it))
        }
        listOf("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "RECITATION").forEach {
            assertEquals(it, AiFailure.Refused(AiFailure.RefusalReason.SAFETY), AiFailure.fromFinishReason(it))
        }
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.CONTENT_FILTER), AiFailure.fromFinishReason("content_filter"))
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL), AiFailure.fromFinishReason("refusal"))
        assertEquals(AiFailure.ProviderProtocol, AiFailure.fromFinishReason("SOMETHING_NEW"))
    }

    @Test
    fun finishReasonFailures_convertToTheTypedExceptions() {
        assertTrue(AiFailure.Truncated.toException() is AiExecutionService.Failure.OutputTruncated)
        assertTrue(AiFailure.Refused(AiFailure.RefusalReason.SAFETY).toException() is AiExecutionService.Failure.Refused)
        assertTrue(AiFailure.ProviderProtocol.toException() is AiTransportException.InvalidResponse)
    }

    @Test
    fun transportException_isTransient_delegatesToTheTaxonomy() {
        assertTrue(AiTransportException.Network().isTransient())
        assertTrue(http(429, AiTransportFailureKind.RATE_LIMITED).isTransient())
        assertTrue(http(502, AiTransportFailureKind.UNKNOWN).isTransient())
        assertFalse(http(429, AiTransportFailureKind.QUOTA_EXHAUSTED).isTransient())
        assertFalse(AiTransportException.InvalidResponse().isTransient())
    }
}
