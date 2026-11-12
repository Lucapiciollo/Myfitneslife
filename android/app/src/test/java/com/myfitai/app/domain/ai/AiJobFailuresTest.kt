package com.myfitai.app.domain.ai

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiJobFailuresTest {
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
    fun permanentErrors_areFailuresWithoutModelText() {
        listOf(
            http(429, AiTransportFailureKind.QUOTA_EXHAUSTED),
            http(401, AiTransportFailureKind.INVALID_API_KEY),
            http(404, AiTransportFailureKind.MODEL_UNAVAILABLE),
            http(400, AiTransportFailureKind.SCHEMA),
            AiTransportException.NotConfigured(AiProviderType.OPENAI),
            AiExecutionService.Failure.OutputTruncated(),
            AiExecutionService.Failure.InvalidSchema(listOf("secret model text")),
            IllegalStateException("boom"),
        ).forEach {
            assertFalse("$it", AiJobFailures.isTransient(it))
            val outcome = AiJobFailures.toOutcome(it)
            assertTrue("$it", outcome is AiJobOutcome.Failure)
            assertFalse((outcome as AiJobOutcome.Failure).message.contains("secret model text"))
        }
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
        assertEquals(Unit, Unit)
    }
}
