package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiContractRegistry
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1: BP1 prompt legend, token budget aligned with the length limits, and the single validator shared with the screen. */
class BodyProportionPromptAndValidatorTest {
    private fun report(comparison: List<BodyProportionEngine.MeasurementComparison> = listOf(waist)) = BodyProportionEngine.Report(
        status = BodyProportionEngine.BalanceStatus.MILD_IMBALANCE,
        maxAsymmetryPercent = 4.5f,
        ratios = listOf(BodyProportionEngine.Ratio("whr", "Vita/fianchi", 0.85f, "d")),
        asymmetries = listOf(BodyProportionEngine.Asymmetry("arm", "Braccio", 4.5f, "destro")),
        availableMeasurements = 4,
        note = "Rapporti descrittivi",
        comparison = comparison,
        currentDate = "2026-10-08",
        previousDate = if (comparison.isEmpty()) null else "2026-09-08",
    )

    private fun interpretation(
        summary: String = "Misure descrittive.",
        comparison: String = "Vita: in calo.",
        observations: List<String> = listOf("Variazione descrittiva."),
        monitor: List<String> = emptyList(),
        validation: String = "valid=1 · ok",
    ) = BodyProportionAnalysisService.Interpretation(summary, observations, monitor, validation, comparison)

    @Test
    fun promptHasLegendAndNoDuplicatedDateRecords() = runBlocking {
        var captured: AiStructuredRequest? = null
        val gateway = object : AiRuntimeGateway {
            override suspend fun execute(
                request: AiStructuredRequest,
                maxSchemaRetries: Int,
                businessValidator: (String) -> Result<Unit>,
            ): AiExecutionService.ValidatedResponse {
                captured = request
                val output = """{"data":"BP1\nS|Misure descrittive.\nC|Vita: in calo.\nV|1|ok"}"""
                businessValidator(output).getOrThrow()
                return AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "test", output)
            }
        }
        BodyProportionAnalysisService(gateway).analyze(report())

        val user = captured!!.userPrompt
        assertEquals(1, Regex("CURRENT_DATE\\|").findAll(user).count())
        assertEquals(1, Regex("PREVIOUS_DATE\\|").findAll(user).count())
        val system = captured!!.systemPrompt
        listOf("B|", "T|", "R|", "A|", "CURRENT_DATE|", "PREVIOUS_DATE|", "APP_COMPARISON|").forEach {
            assertTrue("legend must describe $it", system.contains(it))
        }
        assertTrue(system.contains("not evidence of pathological imbalance"))
        assertTrue(system.contains("dominant limb can differ"))
        assertTrue(system.contains("not fat gain"))
        assertEquals(BodyProportionAnalysisService.MAX_OUTPUT_TOKENS, captured!!.maxOutputTokens)
    }

    @Test
    fun worstCaseAcceptedOutputFitsTheRegistryBudget() {
        val max = interpretation(
            summary = "s".repeat(BodyProportionAnalysisService.MAX_SUMMARY_CHARS),
            comparison = "Vita: " + "c".repeat(BodyProportionAnalysisService.MAX_COMPARISON_CHARS - 6),
            observations = List(BodyProportionAnalysisService.MAX_OBSERVATIONS) { "o".repeat(BodyProportionAnalysisService.MAX_ITEM_CHARS) },
            monitor = List(BodyProportionAnalysisService.MAX_MONITOR_ITEMS) { "n".repeat(BodyProportionAnalysisService.MAX_ITEM_CHARS) },
            validation = "valid=1 · " + "v".repeat(120),
        )
        assertTrue(BodyProportionAnalysisService.validate(max, report()).isSuccess)

        val cap = AiContractRegistry.require("myfitai_body_proportion_pipe_v1").maxOutputTokens!!
        assertEquals(BodyProportionAnalysisService.MAX_OUTPUT_TOKENS, cap)
        // Conservative 3 chars/token plus 100 tokens of envelope/escaping.
        val worstCaseTokens = BodyProportionAnalysisService.WORST_CASE_OUTPUT_CHARS / 3 + 100
        assertTrue("worst case $worstCaseTokens tokens must fit $cap", worstCaseTokens <= cap)
    }

    @Test
    fun overlongValidationNotesAreRejectedSoTheBudgetHolds() {
        val result = BodyProportionAnalysisService.validate(interpretation(validation = "valid=1 · " + "v".repeat(121)), report())
        assertEquals("BODY_VALIDATION_TOO_LONG", result.exceptionOrNull()?.message)
    }

    @Test
    fun screenAndServiceShareTheSameClaimGuardWithNegation() {
        assertTrue(BodyProportionAnalysisService.validate(interpretation(summary = "Non è una diagnosi medica."), report()).isSuccess)
        assertTrue(BodyProportionAnalysisService.validate(interpretation(observations = listOf("Questa lettura non costituisce diagnosi.")), report()).isSuccess)
        assertEquals(
            "UNSUPPORTED_DIAGNOSTIC_OR_CAUSAL_CLAIM",
            BodyProportionAnalysisService.validate(interpretation(summary = "Diagnosi di scoliosi."), report()).exceptionOrNull()?.message,
        )
        // The comparison text is checked too (the old screen check skipped it).
        assertFalse(BodyProportionAnalysisService.validate(interpretation(comparison = "Vita: indica una patologia."), report()).isSuccess)
    }

    @Test
    fun comparisonMayDeclareASingleIndicatorNotEvaluableButNotAnIndicatorTheAppCompared() {
        val ok = BodyProportionAnalysisService.validate(
            interpretation(comparison = "Vita: in calo; Spalle: dati insufficienti per un confronto."),
            report(),
        )
        assertTrue(ok.exceptionOrNull()?.message, ok.isSuccess)

        val falseInsufficient = BodyProportionAnalysisService.validate(
            interpretation(comparison = "Vita: dati insufficienti."),
            report(),
        )
        assertEquals("BODY_FALSE_INSUFFICIENT_COMPARISON", falseInsufficient.exceptionOrNull()?.message)

        val ungrounded = BodyProportionAnalysisService.validate(interpretation(comparison = "Le misure sono cambiate."), report())
        assertEquals("BODY_COMPARISON_NOT_GROUNDED", ungrounded.exceptionOrNull()?.message)
    }

    @Test
    fun withoutPreviousMeasurementTheComparisonMustSayItCannotBeMade() {
        val empty = report(comparison = emptyList())
        assertTrue(BodyProportionAnalysisService.validate(interpretation(comparison = "Dati insufficienti per confrontare."), empty).isSuccess)
        assertEquals(
            "BODY_MISSING_COMPARISON_UNCERTAINTY",
            BodyProportionAnalysisService.validate(interpretation(comparison = "Vita: in calo."), empty).exceptionOrNull()?.message,
        )
    }

    private companion object {
        val waist = BodyProportionEngine.MeasurementComparison("waist", "Vita", 90f, 88f, -2f)
    }
}
