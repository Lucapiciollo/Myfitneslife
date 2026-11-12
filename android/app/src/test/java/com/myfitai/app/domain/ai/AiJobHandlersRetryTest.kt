package com.myfitai.app.domain.ai

import androidx.work.Data
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiImageInput
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import com.myfitai.app.domain.body.BiaImportAiJobHandler
import com.myfitai.app.domain.body.BiaImportService
import com.myfitai.app.domain.food.CheatAdjustmentAiJobHandler
import com.myfitai.app.domain.food.CheatUnderstandingAiJobHandler
import com.myfitai.app.domain.food.NutritionAdviceAiJobHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Handlers must let transient provider errors and cancellation reach the worker (WorkManager retry/cancel)
 * and turn everything else into a Failure whose message never contains text from the model.
 */
class AiJobHandlersRetryTest {
    private val marker = "ZZ-MODEL-TEXT-ZZ"
    private val http = { status: Int, kind: AiTransportFailureKind -> AiTransportException.Http(AiProviderType.OPENAI, status, kind) }

    private class ThrowingGateway(var error: Throwable = IllegalStateException()) : AiRuntimeGateway {
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

    /** One scenario = a way to run a handler that fails with the given error. */
    private class Scenario(val name: String, val fallback: String, val run: suspend (Throwable) -> AiJobOutcome)

    private val imageDir: File = Files.createTempDirectory("ai-images").toFile()
    private val store = AiImageJobStore(imageDir)
    private val imagePath = store.write(AiImageInput("image/jpeg", "AAAA"))
    private val withImage = Data.Builder().putString(AiJobWorker.KEY_IMAGE_PATH, imagePath).build()
    private val gateway = ThrowingGateway()

    private val scenarios = listOf(
        Scenario("BiaImport", "Import BIA non riuscito") { e ->
            gateway.error = e
            BiaImportAiJobHandler(BiaImportService(gateway), store).execute(1L, "job", withImage)
        },
        Scenario("CheatUnderstanding", "Valutazione sgarro non disponibile") { e ->
            CheatUnderstandingAiJobHandler({ throw e }, store).execute(1L, "job", withImage)
        },
        Scenario("CheatAdjustment", "Adattamento sgarro non riuscito") { e ->
            CheatAdjustmentAiJobHandler { _, _ -> throw e }.execute(1L, "job", Data.EMPTY)
        },
        Scenario("NutritionAdvice", "Consiglio non disponibile") { e ->
            NutritionAdviceAiJobHandler { throw e }.execute(
                1L, "job", Data.Builder().putString(NutritionAdviceAiJobHandler.KEY_QUESTION, "Cosa mangio?").build(),
            )
        },
    )

    private fun outcome(s: Scenario, error: Throwable): AiJobOutcome = runBlocking { s.run(error) }

    @Test
    fun transientErrors_areRethrownByEveryHandler() {
        val transient = listOf(
            AiTransportException.Network(),
            http(429, AiTransportFailureKind.RATE_LIMITED),
            http(503, AiTransportFailureKind.PROVIDER_UNAVAILABLE),
        )
        scenarios.forEach { s ->
            transient.forEach { error ->
                try {
                    outcome(s, error)
                    fail("${s.name}: $error must be rethrown")
                } catch (thrown: AiTransportException) {
                    assertTrue("${s.name}: same instance", thrown === error)
                }
            }
        }
        assertTrue("handlers must not delete the image: the worker decides", File(imagePath).exists())
    }

    @Test
    fun cancellation_isRethrownByEveryHandler() {
        scenarios.forEach { s ->
            val cancelled = CancellationException("stop")
            try {
                outcome(s, cancelled)
                fail("${s.name}: cancellation must propagate")
            } catch (thrown: CancellationException) {
                assertTrue(s.name, thrown === cancelled)
            }
        }
    }

    @Test
    fun permanentErrors_becomeFailuresWithoutModelText() {
        val permanent = listOf(
            http(429, AiTransportFailureKind.QUOTA_EXHAUSTED),
            http(401, AiTransportFailureKind.INVALID_API_KEY),
            AiExecutionService.Failure.OutputTruncated(),
            AiExecutionService.Failure.InvalidSchema(listOf(marker)),
            AiExecutionService.Failure.BusinessRejected(marker),
            AiExecutionService.Failure.PaidRetryBudgetConsumed(AiTransportException.Network()),
            IllegalStateException(marker),
            IllegalArgumentException("BIA_RELIABILITY_INVALID_$marker"),
        )
        scenarios.forEach { s ->
            permanent.forEach { error ->
                val result = outcome(s, error)
                assertTrue("${s.name}: $error -> $result", result is AiJobOutcome.Failure)
                val message = (result as AiJobOutcome.Failure).message
                assertFalse("${s.name}: $message", message.contains(marker))
            }
            assertEquals(s.fallback, (outcome(s, IllegalStateException(marker)) as AiJobOutcome.Failure).message)
        }
    }

    @Test
    fun biaImport_hidesTheModelRejectionReason() {
        val result = outcome(scenarios[0], BiaImportService.NotBiaImage(marker))
        assertEquals("La foto non sembra una rilevazione BIA.", (result as AiJobOutcome.Failure).message)
    }

    @Test
    fun biaImport_reachesTheProviderExactlyOncePerAttempt() {
        gateway.calls = 0
        runCatching { outcome(scenarios[0], AiTransportException.Network()) }
        assertEquals(1, gateway.calls)
    }
}