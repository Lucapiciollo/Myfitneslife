package com.myfitai.app.domain.ai

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiFailure
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import com.myfitai.app.domain.body.BiaAnalysisContract
import com.myfitai.app.domain.food.CheatAdjustmentService
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiJobFailuresTest {
    private val marker = "ZZ-MODEL-TEXT-ZZ"
    private fun http(status: Int, kind: AiTransportFailureKind) = AiTransportException.Http(AiProviderType.GEMINI, status, kind)

    @Test
    fun transientErrors_areRetried() {
        listOf(
            AiTransportException.Network(),
            http(429, AiTransportFailureKind.RATE_LIMITED),
            http(503, AiTransportFailureKind.PROVIDER_UNAVAILABLE),
            http(500, AiTransportFailureKind.UNKNOWN),
        ).forEach {
            assertTrue("$it", AiJobFailures.isTransient(it))
            assertTrue("$it", AiJobFailures.toOutcome(it) is AiJobOutcome.Retry)
        }
    }

    @Test
    fun permanentErrors_areFailures() {
        listOf(
            http(429, AiTransportFailureKind.QUOTA_EXHAUSTED),
            http(401, AiTransportFailureKind.INVALID_API_KEY),
            http(404, AiTransportFailureKind.MODEL_UNAVAILABLE),
            http(400, AiTransportFailureKind.SCHEMA),
            AiTransportException.NotConfigured(AiProviderType.OPENAI),
            AiExecutionService.Failure.OutputTruncated(),
            AiExecutionService.Failure.InvalidSchema(listOf(marker)),
            AiExecutionService.Failure.PaidRetryBudgetConsumed(AiTransportException.Network()),
            IllegalStateException("boom"),
        ).forEach {
            assertFalse("$it", AiJobFailures.isTransient(it))
            assertTrue("$it", AiJobFailures.toOutcome(it) is AiJobOutcome.Failure)
        }
    }

    @Test
    fun safeMessage_neverForwardsArbitraryTextFromTheModelOutput() {
        val fallback = "Fallback fisso"
        val errors = listOf(
            IllegalArgumentException("BIA_RELIABILITY_INVALID_$marker"),
            IllegalArgumentException("BA2_L_ARITY_$marker"),
            IllegalArgumentException("UNKNOWN_CODE_$marker"),
            IllegalStateException(marker),
            IllegalArgumentException("$marker: BIA_CLASSIFICATION_INVALID"),
            JSONException("Value $marker of type java.lang.String cannot be converted to JSONObject"),
            AiExecutionService.Failure.BusinessRejected(marker),
            AiExecutionService.Failure.BusinessRejected("BIA_CLASSIFICATION_INVALID: $marker"),
            AiExecutionService.Failure.InvalidSchema(listOf(marker)),
        )
        errors.forEach { error ->
            val message = AiJobFailures.safeMessage(error, fallback)
            assertFalse("$error -> $message", message.contains(marker))
            val allowed = message == fallback || message in AiJobFailures.KNOWN_CODES || Regex("^BA2_[A-Z]_ARITY_\\d{1,3}$").matches(message)
            assertTrue("$error -> $message", allowed)
        }
    }

    @Test
    fun safeMessage_keepsStableCodesAndTruncatesDynamicOnes() {
        assertEquals("BIA_RELIABILITY_INVALID", AiJobFailures.safeMessage(IllegalArgumentException("BIA_RELIABILITY_INVALID_HIGH$marker"), "f"))
        assertEquals("BA2_L_ARITY_3", AiJobFailures.safeMessage(IllegalArgumentException("BA2_L_ARITY_3"), "f"))
        assertEquals("EMPTY_ACTION", AiJobFailures.safeMessage(IllegalArgumentException("EMPTY_ACTION: $marker"), "f"))
        assertEquals("OUTPUT_TRUNCATED", AiJobFailures.safeMessage(AiExecutionService.Failure.OutputTruncated(), "f"))
        assertEquals("INVALID_SCHEMA", AiJobFailures.safeMessage(AiExecutionService.Failure.InvalidSchema(listOf(marker)), "f"))
        assertEquals("TRANSIENT_AFTER_PAID_RETRY", AiJobFailures.safeMessage(AiExecutionService.Failure.PaidRetryBudgetConsumed(AiTransportException.Network()), "f"))
        assertEquals("Provider IA non configurato", AiJobFailures.safeMessage(AiTransportException.NotConfigured(AiProviderType.OPENAI), "f"))
        assertEquals("NEEDS_INPUT: profilo attivo", AiJobFailures.safeMessage(CheatAdjustmentService.AdjustmentException.NeedsInput(listOf("profilo attivo")), "f"))
        assertNull(AiJobFailures.stableCode("not a code $marker"))
    }

    @Test
    fun realBiaReliabilityErrorCarriesNoModelText() {
        val data = "BA2B|STABLE S|Stabile C|Una rilevazione F|? O|? K|? P|Ripeti L|LOW|Storico breve X|Una X|Due X|Tre D|Analisi informativa N|? V|1|ok"
        val parsed = BiaAnalysisContract.parse(JSONObject().put("data", data).toString())
        val error = BiaAnalysisContract.validateBusiness(parsed.copy(reliability = marker)).exceptionOrNull()!!
        assertEquals("BIA_RELIABILITY_INVALID", error.message)
        assertEquals("BIA_RELIABILITY_INVALID", AiJobFailures.safeMessage(error, "f"))
    }

    @Test
    fun rethrowHelper_rethrowsOnlyTransientAndCancellation() {
        val cancelled = CancellationException("c")
        val network = AiTransportException.Network()
        listOf(cancelled, network).forEach { error ->
            val thrown = runCatching { AiJobFailures.rethrowIfRetryableOrCancelled(error) }.exceptionOrNull()
            assertTrue(thrown === error)
        }
        AiJobFailures.rethrowIfRetryableOrCancelled(IllegalStateException("x"))
        AiJobFailures.rethrowIfRetryableOrCancelled(http(429, AiTransportFailureKind.QUOTA_EXHAUSTED))
    }

    @Test
    fun newFailureCategories_arePermanentAndForwardOnlyTheirStableCode() {
        mapOf(
            AiExecutionService.Failure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL) to "REFUSED",
            AiExecutionService.Failure.EmptyOutput() to "EMPTY_OUTPUT",
            // Kept on the legacy code the UI already maps until it consumes AiFailure directly.
            AiExecutionService.Failure.MalformedJson() to "INVALID_SCHEMA",
            AiExecutionService.Failure.ContractNotRegistered(marker) to "CONTRACT_NOT_REGISTERED",
        ).forEach { (error, expected) ->
            assertFalse("$error", AiJobFailures.isTransient(error))
            assertTrue("$error", AiJobFailures.toOutcome(error) is AiJobOutcome.Failure)
            assertEquals(expected, AiJobFailures.safeMessage(error, "f"))
            assertFalse(AiJobFailures.safeMessage(error, "f").contains(marker))
        }
    }

    @Test
    fun transportFailures_surfaceTheClosedUserMessageTable() {
        assertEquals("Provider non raggiungibile", AiJobFailures.safeMessage(AiTransportException.Network(), "f"))
        assertEquals("Risposta provider non interpretabile", AiJobFailures.safeMessage(AiTransportException.InvalidResponse(), "f"))
        assertEquals(AiFailure.Quota.userMessage, AiJobFailures.safeMessage(http(429, AiTransportFailureKind.QUOTA_EXHAUSTED), "f"))
        assertEquals(AiFailure.Auth.userMessage, AiJobFailures.safeMessage(http(401, AiTransportFailureKind.INVALID_API_KEY), "f"))
        assertEquals(
            "Provider non raggiungibile",
            (AiJobFailures.toOutcome(AiTransportException.Network()) as AiJobOutcome.Retry).message,
        )
        assertEquals(
            "Provider temporaneamente non disponibile",
            (AiJobFailures.toOutcome(http(503, AiTransportFailureKind.PROVIDER_UNAVAILABLE)) as AiJobOutcome.Retry).message,
        )
    }

    @Test
    fun knownCodes_isTheSetOwnedByTheTaxonomy() {
        assertEquals(AiFailure.BUSINESS_CODES, AiJobFailures.KNOWN_CODES)
        assertEquals("NEEDS_INPUT: profilo attivo", AiJobFailures.safeMessage(CheatAdjustmentService.AdjustmentException.NeedsInput(listOf("profilo attivo")), "f"))
    }
}