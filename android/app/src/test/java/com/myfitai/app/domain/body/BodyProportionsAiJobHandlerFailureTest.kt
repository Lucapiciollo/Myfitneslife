package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import com.myfitai.app.domain.ai.AiJobOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Transient provider errors must reach WorkManager (retry); permanent ones must become a bounded Failure. */
class BodyProportionsAiJobHandlerFailureTest {
    private val params = Data.Builder().putString(
        BodyProportionsAiJobHandler.KEY_REPORT,
        """{"status":"BALANCED","ratios":[],"asymmetries":[],"availableMeasurements":4,"note":"n","currentDate":"2026-10-08"}""",
    ).build()

    private val gateway = ThrowingGateway()

    private fun handler(error: Throwable): BodyProportionsAiJobHandler {
        gateway.error = error
        return BodyProportionsAiJobHandler(BodyProportionAnalysisService(gateway))
    }

    private fun http(status: Int, kind: AiTransportFailureKind) =
        AiTransportException.Http(AiProviderType.OPENAI, status, kind)

    private fun assertRethrown(error: Throwable) = runBlocking {
        try {
            handler(error).execute(1L, "job", params)
            fail("expected ${error::class.simpleName} to be rethrown")
        } catch (thrown: Throwable) {
            assertTrue("same instance rethrown", thrown === error)
        }
    }

    private fun assertFailure(error: Throwable) = runBlocking {
        val outcome = handler(error).execute(1L, "job", params)
        assertEquals("gateway must have been reached", 1, gateway.calls)
        assertTrue("expected Failure for ${error::class.simpleName}, got $outcome", outcome is AiJobOutcome.Failure)
    }

    @Test fun networkError_isRethrownForRetry() = assertRethrown(AiTransportException.Network())
    @Test fun rateLimit_isRethrownForRetry() = assertRethrown(http(429, AiTransportFailureKind.RATE_LIMITED))
    @Test fun serverError_isRethrownForRetry() = assertRethrown(http(503, AiTransportFailureKind.PROVIDER_UNAVAILABLE))
    @Test fun quotaExhausted_isFailure() = assertFailure(http(429, AiTransportFailureKind.QUOTA_EXHAUSTED))
    @Test fun invalidKey_isFailure() = assertFailure(http(401, AiTransportFailureKind.INVALID_API_KEY))
    @Test fun outputTruncated_isFailure() = assertFailure(AiExecutionService.Failure.OutputTruncated())
    @Test fun invalidSchema_isFailure() = assertFailure(AiExecutionService.Failure.InvalidSchema(listOf("x")))

    @Test
    fun cancellation_isAlwaysRethrown() = assertRethrown(CancellationException("cancelled"))

    @Test
    fun permanentFailure_keepsAMessageWithoutModelText() = runBlocking {
        val outcome = handler(AiExecutionService.Failure.OutputTruncated()).execute(1L, "job", params)
        assertEquals(1, gateway.calls)
        assertEquals("OUTPUT_TRUNCATED", (outcome as AiJobOutcome.Failure).message)
    }

    private class ThrowingGateway : AiRuntimeGateway {
        var error: Throwable = IllegalStateException()
        var calls = 0
        override suspend fun execute(
            request: AiStructuredRequest,
            maxSchemaRetries: Int,
            businessValidator: (String) -> Result<Unit>,
        ): AiExecutionService.ValidatedResponse {
            calls++
            throw error
        }
    }
}