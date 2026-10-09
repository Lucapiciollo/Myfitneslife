package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyProportionAnalysisServiceTest {
    @Test
    fun parsesComparisonAndAgentSelfValidationWhileAppBusinessValidationRemainsLocal() = runBlocking {
        val service = BodyProportionAnalysisService(FixedGateway(validOutput()))
        val result = service.analyze(report())

        assertEquals("Vita: migliora", result.comparison)
        assertTrue(result.agentValidation.contains("valid=1"))
        assertTrue(BodyProportionAnalysisService(FixedGateway(validOutput())).validateBusiness(result).isSuccess)
    }

    @Test
    fun missingComparisonOrSelfValidationIsRejected() = runBlocking {
        val missingComparison = validOutput().replace("C|Vita: migliora", "")
        val error = runCatching { BodyProportionAnalysisService(FixedGateway(missingComparison)).analyze(report()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun unsupportedDiagnosticClaimIsRejectedByAppValidator() = runBlocking {
        val output = validOutput().replace(
            "Le misure recenti mostrano una variazione da valutare nel contesto.",
            "Questa diagnosi certa conferma una patologia.",
        )
        val accepted = runCatching { BodyProportionAnalysisService(FixedGateway(output)).analyze(report()) }
        assertFalse(accepted.isSuccess)
    }

    @Test
    fun rejectsAgentAutovalidationMarkedInvalid() = runBlocking {
        val output = validOutput().replace("V|1|Confronto prudente", "V|0|Dati incoerenti")
        val accepted = runCatching { BodyProportionAnalysisService(FixedGateway(output)).analyze(report()) }
        assertFalse(accepted.isSuccess)
    }

    private fun report() = BodyProportionEngine.Report(
        status = BodyProportionEngine.BalanceStatus.BALANCED,
        maxAsymmetryPercent = 1f,
        ratios = emptyList(),
        asymmetries = emptyList(),
        availableMeasurements = 4,
        note = "Rapporti descrittivi",
        comparison = listOf(BodyProportionEngine.MeasurementComparison("waist", "Vita", 90f, 88f, -2f)),
        currentDate = "2026-10-08",
        previousDate = "2026-09-08",
    )

    private fun validOutput(summary: String = "Le misure recenti mostrano una variazione da valutare nel contesto.") = """
        {"data":"BP1\nS|$summary\nC|Vita: migliora\nO|La variazione è descrittiva e non dimostra un cambiamento di grasso\nN|Ripetere le misure in condizioni simili\nV|1|Confronto prudente"}
    """.trimIndent()

    private class FixedGateway(private val output: String) : AiRuntimeGateway {
        override suspend fun execute(
            request: AiStructuredRequest,
            maxSchemaRetries: Int,
            businessValidator: (String) -> Result<Unit>,
        ): AiExecutionService.ValidatedResponse {
            if (request.schemaName == "myfitai_body_proportion_pipe_v1") {
                val records = com.myfitai.app.ai.AiCompactEnvelope.data(output).lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                require(records.firstOrNull() == "BP1")
                require(records.any { it.startsWith("S|") } && records.any { it.startsWith("C|") } && records.any { it.startsWith("V|") })
                require(records.first { it.startsWith("V|") }.split('|').getOrNull(1) == "1")
            }
            assertTrue(request.userPrompt.contains("PREVIOUS_DATE|2026-09-08"))
            assertTrue(request.userPrompt.contains("APP_COMPARISON|Vita:90.0:88.0:-2.0:cm"))
            businessValidator(output).getOrThrow()
            return AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "test", output)
        }
    }
}
