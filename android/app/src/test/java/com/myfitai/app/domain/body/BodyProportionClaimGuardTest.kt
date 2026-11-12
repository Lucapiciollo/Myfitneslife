package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyProportionClaimGuardTest {
    private val service = BodyProportionAnalysisService(object : AiRuntimeGateway {
        override suspend fun execute(
            request: AiStructuredRequest,
            maxSchemaRetries: Int,
            businessValidator: (String) -> Result<Unit>,
        ): AiExecutionService.ValidatedResponse = error("not used")
    })

    private fun interpretation(summary: String = "Misure descrittive.", observation: String = "Variazione descrittiva.") =
        BodyProportionAnalysisService.Interpretation(summary, listOf(observation), emptyList(), "valid=1", "Vita: migliora")

    @Test
    fun negatedDiagnosisIsAccepted() {
        listOf(
            "Non è una diagnosi medica.",
            "Questa lettura non costituisce diagnosi.",
            "Descrizione senza diagnosi.",
            "Nessuna diagnosi è formulata.",
        ).forEach { text ->
            val result = service.validateBusiness(interpretation(summary = text))
            assertTrue("$text: ${result.exceptionOrNull()?.message}", result.isSuccess)
        }
    }

    @Test
    fun assertedDiagnosisIsRejectedEvenIfAnotherFieldDisclaims() {
        listOf(
            interpretation(summary = "Diagnosi di scoliosi.", observation = "Non è una diagnosi."),
            interpretation(summary = "Non è una diagnosi.", observation = "Indica una patologia."),
            interpretation(observation = "Questa è una causa certa."),
        ).forEach { value ->
            assertEquals("UNSUPPORTED_DIAGNOSTIC_OR_CAUSAL_CLAIM", service.validateBusiness(value).exceptionOrNull()?.message)
        }
    }
}
