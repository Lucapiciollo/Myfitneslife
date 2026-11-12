package com.myfitai.app.domain.ai

import androidx.work.Data
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AiJobRunnerTest {
    private class FakeHandler(private val action: () -> AiJobOutcome) : AiJobHandler {
        var calls = 0
        override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
            calls++
            return action()
        }
    }

    private fun run(handler: AiJobHandler, attempt: Int, image: File): AiJobDecision = runBlocking {
        AiJobRunner.run(handler, 1L, "job", Data.EMPTY, attempt, image.absolutePath) { path -> path?.let { File(it).delete() } }
    }

    private fun image(): File = File.createTempFile("bia-image", ".payload").apply { writeText("image/jpeg\nAAAA") }

    private val transient = AiTransportException.Network()

    @Test
    fun transientErrorOnFirstAttempt_retriesAndKeepsTheImage() {
        val image = image()
        val decision = run(FakeHandler { throw transient }, attempt = 0, image = image)
        assertTrue("got $decision", decision is AiJobDecision.Retry)
        assertTrue("image must survive until the next attempt", image.exists())
        image.delete()
    }

    @Test
    fun transientErrorOnLastAttempt_failsAndDeletesTheImage() {
        val image = image()
        val decision = run(FakeHandler { throw transient }, attempt = AiJobWorker.MAX_RETRY_ATTEMPTS, image = image)
        assertTrue("got $decision", decision is AiJobDecision.Failure)
        assertFalse(image.exists())
    }

    @Test
    fun retryOutcomeFromAHandler_followsTheSameAttemptRule() {
        val image = image()
        assertTrue(run(FakeHandler { AiJobOutcome.Retry("again") }, attempt = AiJobWorker.MAX_RETRY_ATTEMPTS - 1, image = image) is AiJobDecision.Retry)
        assertTrue(image.exists())
        assertTrue(run(FakeHandler { AiJobOutcome.Retry("again") }, attempt = AiJobWorker.MAX_RETRY_ATTEMPTS, image = image) is AiJobDecision.Failure)
        assertFalse(image.exists())
    }

    @Test
    fun permanentFailureAndSuccess_deleteTheImageImmediately() {
        val quota = AiTransportException.Http(AiProviderType.OPENAI, 429, AiTransportFailureKind.QUOTA_EXHAUSTED)
        val failureImage = image()
        assertTrue(run(FakeHandler { throw quota }, attempt = 0, image = failureImage) is AiJobDecision.Failure)
        assertFalse(failureImage.exists())

        val successImage = image()
        assertTrue(run(FakeHandler { AiJobOutcome.Success(Data.EMPTY) }, attempt = 0, image = successImage) is AiJobDecision.Success)
        assertFalse(successImage.exists())
    }

    @Test
    fun cancellation_isRethrownAndNeverTurnedIntoAFailure() {
        val image = image()
        val cancelled = CancellationException("stop")
        try {
            run(FakeHandler { throw cancelled }, attempt = 0, image = image)
            fail("CancellationException must propagate")
        } catch (thrown: CancellationException) {
            assertTrue(thrown === cancelled)
        }
        image.delete()
    }

    @Test
    fun outOfMemory_isMappedToAFailureAndTheImageIsCleaned() {
        val image = image()
        val decision = run(FakeHandler { throw OutOfMemoryError("ZZ-model-text") }, attempt = 0, image = image)
        assertTrue("got $decision", decision is AiJobDecision.Failure)
        assertFalse((decision as AiJobDecision.Failure).message.contains("ZZ-model-text"))
        assertFalse(image.exists())
        assertEquals("Operazione IA non riuscita", decision.message)
    }
}