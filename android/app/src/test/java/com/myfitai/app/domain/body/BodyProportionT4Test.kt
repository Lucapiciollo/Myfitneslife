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

/**
 * T4 of the body proportions: dates, noise threshold for circumferences (reversible assumption, declared), app-written
 * comparison text and the validator that forbids calling a change above the threshold "normal fluctuation".
 */
class BodyProportionT4Test {
    private fun c(key: String, label: String, previous: Float, current: Float, unit: String = "cm") =
        BodyProportionEngine.MeasurementComparison(key, label, previous, current, current - previous, unit)

    // The observed case: hips +4 cm and abdomen +3 cm in 3.5 months, waist unchanged.
    private val hips = c("hips", "Fianchi", 100f, 104f)
    private val abdomen = c("abdomen", "Addome", 88f, 91f)
    private val waist = c("waist", "Vita", 90f, 90f)

    private fun report(vararg comparison: BodyProportionEngine.MeasurementComparison) = BodyProportionEngine.Report(
        status = BodyProportionEngine.BalanceStatus.BALANCED,
        maxAsymmetryPercent = 2f,
        ratios = emptyList(),
        asymmetries = emptyList(),
        availableMeasurements = 6,
        note = "Rapporti descrittivi",
        comparison = comparison.toList(),
        currentDate = "2026-11-08",
        previousDate = "2026-07-25",
    )

    private fun interpretation(
        summary: String = "Misure descrittive.",
        comparison: String = "Fianchi e addome sono aumentati; la vita e invariata.",
        observations: List<String> = emptyList(),
        monitor: List<String> = emptyList(),
    ) = BodyProportionAnalysisService.Interpretation(summary, observations, monitor, "valid=1 - ok", comparison)

    // ---- noise threshold ---------------------------------------------------------------------------------------

    @Test
    fun circumferenceThresholdIsDeclaredAndRoundedToOneDecimal() {
        assertEquals(1.0, BiaNoiseThresholds.CIRCUMFERENCE_CM, 0.0)
        assertEquals(1.0, BiaNoiseThresholds.forBodyMeasurement("cm")!!, 0.0)
        assertEquals(BiaNoiseThresholds.WEIGHT_KG, BiaNoiseThresholds.forBodyMeasurement("kg")!!, 0.0)
        assertEquals(null, BiaNoiseThresholds.forBodyMeasurement("mm"))
        assertTrue(BiaNoiseThresholds.isAboveBodyMeasurementNoise("cm", 4.0))
        assertTrue(BiaNoiseThresholds.isAboveBodyMeasurementNoise("cm", -1.5))
        assertFalse(BiaNoiseThresholds.isAboveBodyMeasurementNoise("cm", 1.0))
        assertFalse(BiaNoiseThresholds.isAboveBodyMeasurementNoise("cm", -0.5))
        // Float noise of the subtraction (90.3f - 89.3f = 1.0000076) is not a change above the threshold.
        assertFalse(BiaNoiseThresholds.isAboveBodyMeasurementNoise("cm", (90.3f - 89.3f).toDouble()))
        // Unknown unit: nothing is claimed.
        assertFalse(BiaNoiseThresholds.isAboveBodyMeasurementNoise("mm", 50.0))
    }

    @Test
    fun comparisonKnowsIfItIsAboveTheNoiseThreshold() {
        assertTrue(hips.aboveNoise)
        assertTrue(abdomen.aboveNoise)
        assertFalse(waist.aboveNoise)
        assertFalse(c("weight", "Peso", 80f, 80.5f, "kg").aboveNoise)
        assertTrue(c("weight", "Peso", 80f, 81f, "kg").aboveNoise)
    }

    // ---- prompt ------------------------------------------------------------------------------------------------

    @Test
    fun promptSendsTheOutcomeAgainstTheThresholdForEveryMeasureAndDeclaresTheThreshold() = runBlocking {
        var captured: AiStructuredRequest? = null
        val gateway = object : AiRuntimeGateway {
            override suspend fun execute(
                request: AiStructuredRequest,
                maxSchemaRetries: Int,
                businessValidator: (String) -> Result<Unit>,
            ): AiExecutionService.ValidatedResponse {
                captured = request
                val output = """{"data":"BP1\nS|Misure descrittive.\nC|Fianchi e addome sono aumentati; la vita e invariata.\nV|1|ok"}"""
                businessValidator(output).getOrThrow()
                return AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "test", output)
            }
        }
        BodyProportionAnalysisService(gateway).analyze(report(hips, abdomen, waist))

        val user = captured!!.userPrompt
        assertTrue(user, user.contains("Fianchi:100.0:104.0:4.0:cm:ABOVE_THRESHOLD"))
        assertTrue(user, user.contains("Addome:88.0:91.0:3.0:cm:ABOVE_THRESHOLD"))
        assertTrue(user, user.contains("Vita:90.0:90.0:0.0:cm:BELOW_THRESHOLD"))
        assertTrue(user, user.contains("NOISE_THRESHOLDS|cm=1.0"))
        val system = captured!!.systemPrompt
        assertTrue(system.contains("ABOVE_THRESHOLD"))
        assertTrue(system.contains("BELOW_THRESHOLD"))
        assertTrue(system.contains("NOISE_THRESHOLDS"))
        assertTrue(system.contains("never call"))
    }

    // ---- validator ---------------------------------------------------------------------------------------------

    private fun error(value: BodyProportionAnalysisService.Interpretation, source: BodyProportionEngine.Report? = report(hips, abdomen, waist)) =
        BodyProportionAnalysisService.validate(value, source).exceptionOrNull()?.message

    @Test
    fun validator_rejectsCallingAChangeAboveTheThresholdNormalFluctuation() {
        // The text observed on device.
        val observed = interpretation(
            comparison = "Fianchi (+4 cm) e addome (+3 cm) rientrano nelle normali oscillazioni corporee.",
        )
        assertEquals("BODY_ABOVE_THRESHOLD_CALLED_NOISE:Fianchi", error(observed))
        assertEquals("BODY_ABOVE_THRESHOLD_CALLED_NOISE:Fianchi", error(interpretation(summary = "I fianchi rientrano nel rumore di misura.")))
        assertEquals("BODY_ABOVE_THRESHOLD_CALLED_NOISE:Addome", error(interpretation(observations = listOf("Addome stabile rispetto alla misura precedente."))))
        assertEquals("BODY_ABOVE_THRESHOLD_CALLED_NOISE:Fianchi", error(interpretation(monitor = listOf("Fianchi +4 cm, normale oscillazione."))))
        // The phrase can follow the subject after a comma.
        assertEquals("BODY_ABOVE_THRESHOLD_CALLED_NOISE:Fianchi", error(interpretation(comparison = "Fianchi +4 cm, rientrano nelle normali oscillazioni.")))
        assertEquals("BODY_ABOVE_THRESHOLD_CALLED_NOISE:Fianchi", error(interpretation(comparison = "Vita e fianchi stabili.")))
    }

    @Test
    fun validator_acceptsNegationAndIndicatorsBelowTheThreshold() {
        assertTrue(error(interpretation(comparison = "Fianchi e addome non rientrano nelle normali oscillazioni."), report(hips, abdomen, waist)) == null)
        assertTrue(error(interpretation(comparison = "I fianchi non sono stabili: la variazione supera la soglia."), report(hips, waist)) == null)
        // The waist is below the threshold: calling it stable is correct, even in the same sentence as an indicator above.
        assertTrue(error(interpretation(comparison = "Vita stabile, fianchi +4 cm oltre la soglia.")) == null)
        assertTrue(error(interpretation(comparison = "Vita stabile entro la soglia. Fianchi e addome oltre la soglia di rumore.")) == null)
        // A generic sentence that names no indicator above the threshold is not checked.
        assertTrue(error(interpretation(comparison = "Fianchi e addome oltre la soglia; le altre misure rientrano nelle normali oscillazioni.")) == null)
    }

    @Test
    fun validator_withoutSourceKeepsTheOldBehaviour() {
        assertTrue(error(interpretation(comparison = "Fianchi stabili."), null) == null)
    }

    // ---- app comparison text ----------------------------------------------------------------------------------

    @Test
    fun appComparison_formatsDatesInItalianAndStatesTheThresholdOutcome() {
        val text = BodyComparisonText.appComparison(report(hips, waist))

        val lines = text.lines()
        assertEquals("Precedente: 25/07/2026, ultima: 08/11/2026", lines[0])
        assertEquals("Fianchi: da 100,0 cm a 104,0 cm (variazione +4,0 cm, oltre la soglia di rumore di 1,0 cm)", lines[1])
        assertEquals("Vita: da 90,0 cm a 90,0 cm (variazione +0,0 cm, entro la soglia di rumore di 1,0 cm)", lines[2])
        assertFalse(text.contains("2026-"))
        assertFalse(text.contains("  "))
        assertFalse(text.contains("\u2192"))
    }

    @Test
    fun appComparison_withoutPreviousMeasurementKeepsTheOldSentence() {
        val empty = report().copy(previousDate = null)
        assertTrue(BodyComparisonText.appComparison(empty).startsWith("Una sola rilevazione"))
    }

    @Test
    fun line_usesAnExplicitFromToInsteadOfAnArrow() {
        assertEquals("Vita: da 90,0 cm a 90,0 cm (variazione +0,0 cm)", BodyComparisonText.line(waist))
        assertEquals("Peso: da 80,0 kg a 79,0 kg (variazione -1,0 kg)", BodyComparisonText.line(c("weight", "Peso", 80f, 79f, "kg")))
    }

    @Test
    fun dates_areShownAsDayMonthYearAndOldSavedTextIsLocalised() {
        assertEquals("25/07/2026", BodyComparisonText.formatDate("2026-07-25"))
        assertEquals("?", BodyComparisonText.formatDate("?"))
        assertEquals("non-data", BodyComparisonText.formatDate("non-data"))
        // Result saved before T4: ISO dates inside the stored comparison.
        assertEquals(
            "Precedente 25/07/2026 \u2192 ultima 08/11/2026\nVita: 90,0 cm",
            BodyComparisonText.localizeIsoDates("Precedente 2026-07-25 \u2192 ultima 2026-11-08\nVita: 90,0 cm"),
        )
        assertEquals("Nessuna data", BodyComparisonText.localizeIsoDates("Nessuna data"))
    }
}
