package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.body.BiaAnalysisPresentation as P
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 of the BIA Coach: quality of the REAL output observed on device with Gemini (12/11/2026). The strings used as
 * fixtures are the ones the model really produced.
 */
class BiaCoachT4Test {
    private fun interpretation(
        classification: String = "STABLE",
        verdict: String = "Andamento stabile con grasso corporeo in lieve calo.",
        objective: String = "L'obiettivo di ricomposizione e coerente con i dati.",
        calories: String = "Il target operativo e coerente con l'obiettivo e le proteine restano ben distribuite.",
        positives: List<String> = listOf("La massa magra resta stabile tra le due misure."),
        monitor: List<String> = listOf("Il grasso corporeo va riconfermato con una nuova misura."),
        actions: List<String> = listOf("Ripeti la misura nelle stesse condizioni.", "Mantieni il piano attuale.", "Distribuisci le proteine nei pasti."),
    ): BiaAnalysisContract.Interpretation = BiaAnalysisContract.parse(
        JSONObject().put(
            "data",
            (
                listOf(
                    "BA2", "B|$classification", "S|$verdict", "C|Il trend resta nel rumore.", "F|?", "O|$objective", "K|$calories",
                    "P|Ripeti la misura tra tre settimane.", "L|MEDIUM|Storico coerente.",
                ) + positives.map { "Y|$it" } + monitor.map { "M|$it" } + actions.map { "X|$it" } + listOf("N|?", "V|1|ok")
                ).joinToString("\n"),
        ).toString(),
    )

    private val context = BiaAnalysisContract.ValidationContext(
        contextText = "TARGET_ABITUALE_KCAL:tdee=2974,target=2528,p=179,c=292,f=72\nTARGET_OPERATIVO_OGGI_KCAL:tipo_giorno=RIPOSO,tdee=2638,target=2242,p=179,c=220,f=72\n" +
            "BIA_CORRENTE:weightKg=89.5,bodyFatPercent=19.9",
    )

    private fun error(value: BiaAnalysisContract.Interpretation, validation: BiaAnalysisContract.ValidationContext = context) =
        BiaAnalysisContract.validateBusinessWith(value, validation).exceptionOrNull()?.message

    // ---- (1) placeholders / enum tokens instead of sentences -----------------------------------------------

    @Test
    fun validator_rejectsTheEnumTokensObservedInPositivesAndMonitor() {
        val observed = interpretation(positives = listOf("PROBABLY_POSITIVE"), monitor = listOf("MONITOR"))

        assertEquals("BIA_PLACEHOLDER_TEXT:positives", error(observed))
        // Same rule without the number context (old callers).
        assertEquals("BIA_PLACEHOLDER_TEXT:positives", error(observed, BiaAnalysisContract.ValidationContext()))
        assertEquals("BIA_PLACEHOLDER_TEXT:monitor", error(interpretation(monitor = listOf("MONITOR"))))
    }

    @Test
    fun validator_rejectsAnyUppercaseUnderscoreTokenOrContractEnumValue() {
        listOf("SOME_NEW_TOKEN", "FAT_LOSS_WITH_STABLE_MUSCLE", "STABLE", "NEGATIVE", "INSUFFICIENT_DATA", "HIGH", "low", "Medium", "positive.").forEach { token ->
            val message = error(interpretation(positives = listOf(token)))
            assertTrue("$token -> $message", message?.startsWith("BIA_PLACEHOLDER_TEXT:positives") == true)
        }
        assertEquals("BIA_PLACEHOLDER_TEXT:actions", error(interpretation(actions = listOf("MONITOR", "Mantieni il piano attuale.", "Ripeti la misura."))))
    }

    @Test
    fun validator_rejectsListItemsWithLessThanThreeWords() {
        assertEquals("BIA_TEXT_TOO_SHORT:positives", error(interpretation(positives = listOf("Massa stabile"))))
        assertEquals("BIA_TEXT_TOO_SHORT:monitor", error(interpretation(monitor = listOf("Grasso"))))
        assertTrue(BiaAnalysisContract.validateBusinessWith(interpretation(positives = listOf("Massa magra stabile")), context).isSuccess)
    }

    @Test
    fun validator_rejectsAVerdictThatIsTheClassification() {
        assertEquals("BIA_VERDICT_EQUALS_CLASSIFICATION", error(interpretation(verdict = "STABLE")))
        assertEquals("BIA_VERDICT_EQUALS_CLASSIFICATION", error(interpretation(verdict = "stable.")))
        assertEquals("BIA_VERDICT_EQUALS_CLASSIFICATION", error(interpretation(classification = "PROBABLY_POSITIVE", verdict = "Probably positive")))
        // Another enum value is a placeholder as well.
        assertEquals("BIA_PLACEHOLDER_TEXT:verdict", error(interpretation(verdict = "MONITOR")))
    }

    @Test
    fun validator_acceptsRealSentences() {
        val result = BiaAnalysisContract.validateBusinessWith(interpretation(), context)
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
    }

    @Test
    fun protocolAndPromptCarryTheNegativeExample() {
        listOf(BiaAnalysisContract.schemaJson, BiaAnalysisService.SYSTEM_PROMPT).joinToString("\n").let { text ->
            assertTrue(text, text.contains("Y|PROBABLY_POSITIVE"))
            assertTrue(text.contains("M|MONITOR"))
            assertTrue(text.contains("S|STABLE"))
        }
        assertTrue(BiaAnalysisService.SYSTEM_PROMPT.contains("almeno 3 parole"))
    }

    // ---- (3) calories and macros are written by the app ------------------------------------------------------

    @Test
    fun validator_rejectsKcalAndGramFiguresInObjectiveAndCaloriesText() {
        // The observed case: 2974 is the TDEE, but the model called it "target abituale". The number is in the context
        // (NUMBER_NOT_IN_CONTEXT cannot catch it), the figure itself is not allowed.
        val observed = interpretation(calories = "Il target abituale 2974 kcal e coerente con l'obiettivo.")
        assertEquals("BIA_CALORIES_TEXT_HAS_FIGURES:caloriesAndMacros", error(observed))
        assertEquals("BIA_CALORIES_TEXT_HAS_FIGURES:caloriesAndMacros", error(interpretation(calories = "Proteine a 179 g al giorno.")))
        assertEquals("BIA_CALORIES_TEXT_HAS_FIGURES:objective", error(interpretation(objective = "Con 2242 kcal l'obiettivo e raggiungibile.")))
        // The number guard keeps priority: an invented figure is still reported as such.
        assertEquals("NUMBER_NOT_IN_CONTEXT:2527 kcal", error(interpretation(calories = "Calorie target 2527 kcal.")))
        // Qualitative comment, and kg or % elsewhere in the text, stay valid.
        assertTrue(BiaAnalysisContract.validateBusinessWith(interpretation(calories = "Nessuna modifica necessaria al momento."), context).isSuccess)
    }

    @Test
    fun legacyValidationWithoutContextStillAcceptsFiguresInCaloriesText() {
        val legacy = interpretation(calories = "Calorie circa 2100 e macro compatibili.")
        assertTrue(BiaAnalysisContract.validateBusiness(legacy).isSuccess)
    }

    private val calculation = LocalCalculationEngine.Result(
        bmi = 25.3, bmrKcal = 1789.4, tdeeKcal = 2974.2, targetKcal = 2528.1, proteinG = 179.0, fatG = 72.0, carbsG = 292.0,
        waistHeightRatio = null, bmrMethod = "MIFFLIN_ST_JEOR",
    )

    @Test
    fun caloriesSection_isWrittenByTheAppWithEveryTargetLabelledAndTheTdeeNamedAsConsumption() {
        val operational = BiaAnalysisReport.OperationalTarget("RIPOSO", 2638.3, 2242.4, 179.0, 220.0, 72.0)

        val text = BiaAnalysisReport.caloriesSection(calculation, operational)

        val lines = text.lines()
        assertEquals("Oggi (giorno di riposo): target 2242 kcal, proteine 179 g, carboidrati 220 g, grassi 72 g", lines[0])
        assertEquals("Consumo operativo stimato oggi (TDEE): 2638 kcal", lines[1])
        assertEquals("Target abituale: 2528 kcal, proteine 179 g, carboidrati 292 g, grassi 72 g", lines[2])
        assertEquals("Consumo abituale stimato (TDEE): 2974 kcal", lines[3])
        assertEquals(4, lines.size)
        // The TDEE is never written as a target.
        assertFalse(text.contains("target 2974"))
        assertFalse(text.contains("Target abituale: 2974"))
    }

    @Test
    fun caloriesSection_workoutDayAndMissingValues() {
        val workout = BiaAnalysisReport.OperationalTarget("ALLENAMENTO", 3100.0, 2640.0, null, null, null)
        assertEquals("Oggi (giorno di allenamento): target 2640 kcal", BiaAnalysisReport.caloriesSection(null, workout).lines().first())
        assertEquals("", BiaAnalysisReport.caloriesSection(null, null))
        assertEquals("Target abituale: 2528 kcal, proteine 179 g, carboidrati 292 g, grassi 72 g\nConsumo abituale stimato (TDEE): 2974 kcal", BiaAnalysisReport.caloriesSection(calculation, null))
    }

    @Test
    fun serviceHandsTheAppCaloriesTextToTheResultAndThePayloadStoresIt() = runBlocking {
        val data = listOf(
            "BA2", "B|STABLE", "S|Andamento stabile nel periodo.", "C|Il trend resta nel rumore.", "F|?", "O|?", "K|Il target e coerente con l'obiettivo.",
            "P|Ripeti la misura.", "L|MEDIUM|Storico coerente.", "Y|La massa magra resta stabile.", "M|Il grasso va riconfermato presto.",
            "X|Una azione", "X|Due azioni", "X|Tre azioni", "N|?", "V|1|ok",
        ).joinToString("\n")
        val gateway = object : AiRuntimeGateway {
            override suspend fun execute(
                request: AiStructuredRequest,
                maxSchemaRetries: Int,
                businessValidator: (String) -> Result<Unit>,
            ): AiExecutionService.ValidatedResponse {
                val json = JSONObject().put("data", data).toString()
                businessValidator(json).getOrThrow()
                return AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "fake", json)
            }
        }
        val section = "Target abituale: 2528 kcal"

        val result = BiaAnalysisService(gateway).analyze(BiaAnalysisService.Report("BIA_CORRENTE:weightKg=89.5", 2, appCalories = section))

        assertEquals(section, result.appCalories)
        assertEquals(section, JSONObject(BiaAnalysisAiJobHandler.payloadJson(result)).getString("appCalories"))
        // Without app text (legacy callers) the key is absent: saved payloads keep their old shape.
        val legacy = BiaAnalysisService.Result(result.interpretation, "GEMINI", "m")
        assertFalse(JSONObject(BiaAnalysisAiJobHandler.payloadJson(legacy)).has("appCalories"))
    }

    // ---- (2) classification label, verdict ----------------------------------------------------------------------

    @Test
    fun classificationLabel_isAClosedItalianMapForEveryContractValue() {
        assertEquals("Positivo", P.classificationLabel("POSITIVE"))
        assertEquals("Probabilmente positivo", P.classificationLabel("PROBABLY_POSITIVE"))
        assertEquals("Stabile", P.classificationLabel("STABLE"))
        assertEquals("Da monitorare", P.classificationLabel("MONITOR"))
        assertEquals("Negativo", P.classificationLabel("NEGATIVE"))
        assertEquals("Dati insufficienti", P.classificationLabel("INSUFFICIENT_DATA"))
        // Case, spaces and the Italian synonyms accepted by the parser.
        assertEquals("Stabile", P.classificationLabel(" stable "))
        assertEquals("Stabile", P.classificationLabel("STABILE"))
        assertEquals("Dati insufficienti", P.classificationLabel("dati insufficienti"))
        // Anything else is never shown raw.
        assertNull(P.classificationLabel("FAT_LOSS_WITH_STABLE_MUSCLE"))
        assertNull(P.classificationLabel(""))
        assertNull(P.classificationLabel("?"))
    }

    @Test
    fun summaryText_showsTheLabelAndHidesAVerdictThatIsTheCodeOrEmpty() {
        // Observed: verdict = STABLE.
        assertEquals("Esito: Stabile", P.summaryText("STABLE", "STABLE"))
        assertEquals("Esito: Stabile", P.summaryText("STABLE", ""))
        assertEquals("Esito: Stabile", P.summaryText("STABLE", "?"))
        assertEquals("Esito: Stabile", P.summaryText("STABLE", "stable."))
        assertEquals("Esito: Stabile", P.summaryText("STABLE", "MONITOR"))
        assertEquals("Esito: Stabile", P.summaryText("STABLE", "Stabile."))
        assertEquals("Esito: Stabile\nAndamento stabile con grasso in lieve calo.", P.summaryText("STABLE", "Andamento stabile con grasso in lieve calo."))
        // Decimals of the model text are shown with the comma.
        assertEquals("Esito: Positivo\nPeso 89,5 kg.", P.summaryText("POSITIVE", "Peso 89.5 kg."))
        // Without a known classification only a real verdict is shown.
        assertEquals("Andamento in osservazione.", P.summaryText("???", "Andamento in osservazione."))
        assertEquals("", P.summaryText("???", "STABLE"))
    }

    @Test
    fun displayItems_dropPlaceholdersOfResultsSavedBeforeT4() {
        val saved = listOf("PROBABLY_POSITIVE", "MONITOR", "?", "", "La massa magra resta stabile.")
        assertEquals(listOf("La massa magra resta stabile."), P.displayItems(saved))
        assertEquals(listOf("Peso 89,5 kg."), P.displayItems(listOf("Peso 89.5 kg.")))
    }

    // ---- (4) indicator names and percentage points ---------------------------------------------------------------

    @Test
    fun indicatorLabel_mapsRawFieldNamesToItalian() {
        assertEquals("Peso", P.indicatorLabel("weightKg"))
        assertEquals("Peso", P.indicatorLabel("weightKg:"))
        assertEquals("Grasso corporeo", P.indicatorLabel("bodyFatPercent"))
        assertEquals("Massa muscolare", P.indicatorLabel("muscleMassKg"))
        assertEquals("Muscolo scheletrico", P.indicatorLabel("skeletalMuscleKg"))
        assertEquals("Massa magra", P.indicatorLabel("leanMassKg"))
        assertEquals("Massa grassa", P.indicatorLabel("fatMassKg"))
        assertEquals("Acqua corporea (kg)", P.indicatorLabel("bodyWaterKg"))
        assertEquals("Acqua corporea (%)", P.indicatorLabel("bodyWaterPercent"))
        assertEquals("BMI", P.indicatorLabel("bmi"))
        assertEquals("Metabolismo basale", P.indicatorLabel("bmrKcal"))
        assertEquals("Grasso viscerale", P.indicatorLabel("visceralFatLevel"))
        // Already Italian or unknown: untouched (only the trailing colon is dropped).
        assertEquals("Grasso corporeo", P.indicatorLabel("Grasso corporeo"))
        assertEquals("Circonferenza vita", P.indicatorLabel("Circonferenza vita:"))
    }

    @Test
    fun comparisonDifference_usesPercentagePointsForPercentIndicatorsAndTheDecimalComma() {
        assertEquals("-0,5 pp", P.comparisonDifference("bodyFatPercent", "-0.5 %"))
        assertEquals("-0,5 pp", P.comparisonDifference("Grasso corporeo", "-0.5%"))
        assertEquals("+1,2 pp", P.comparisonDifference("bodyWaterPercent", "+1.2 %"))
        // Not a percent-valued indicator: the unit stays.
        assertEquals("-1,8 kg", P.comparisonDifference("weightKg", "-1.8 kg"))
        assertEquals("-2 %", P.comparisonDifference("weightKg", "-2 %"))
    }

    @Test
    fun displayText_replacesRawFieldNamesInsideProse() {
        assertEquals("Il Peso e il Grasso corporeo sono stabili.", P.displayText("Il weightKg e il bodyFatPercent sono stabili."))
        assertEquals("La Massa muscolare cala di 0,5 kg.", P.displayText("La muscleMassKg cala di 0.5 kg."))
    }

    // ---- (5) safety note ----------------------------------------------------------------------------------------

    @Test
    fun safetyNote_neutralSentencesAreNotShown() {
        listOf(
            "Nessuna nota di sicurezza attiva.", "nessuna nota di sicurezza attiva", "  NESSUNA NOTA DI SICUREZZA ATTIVA!  ", "Nessuna nota di sicurezza.",
            "Nessuna nota.", "Nessun avviso di sicurezza.", "Nessuna segnalazione.", "?", "", "-", BiaAnalysisAiJobHandler.DEFAULT_SAFETY_NOTE,
        ).forEach { assertNull("'$it'", P.safetyNoteToShow(it)) }
        assertEquals(
            "Parlane con il tuo medico o con un nutrizionista.",
            P.safetyNoteToShow(" Parlane con il tuo medico o con un nutrizionista. "),
        )
        // A real note that merely contains the words is kept.
        assertEquals(
            "Nessuna nota di sicurezza, ma se compaiono sintomi parla con il medico.",
            P.safetyNoteToShow("Nessuna nota di sicurezza, ma se compaiono sintomi parla con il medico."),
        )
    }

    // ---- (6) decimals --------------------------------------------------------------------------------------------

    @Test
    fun decimals_dotBecomesCommaOnlyForNumbersWithAUnit() {
        assertEquals("Perso 1,8 kg e 89,5 kg", P.normalizeDecimals("Perso 1.8 kg e 89.5 kg"))
        assertEquals("19,9 % e 0,5% e 3,5 cm e 2527,5 kcal", P.normalizeDecimals("19.9 % e 0.5% e 3.5 cm e 2527.5 kcal"))
        // Thousands separators, dates, versions and bare numbers are never touched.
        assertEquals("2.528 kcal", P.normalizeDecimals("2.528 kcal"))
        assertEquals("Il 2026-07-25 e la v1.2 e 3.4", P.normalizeDecimals("Il 2026-07-25 e la v1.2 e 3.4"))
        assertEquals("1.234.5 kg", P.normalizeDecimals("1.234.5 kg"))
        assertEquals("1.8 kgx", P.normalizeDecimals("1.8 kgx"))
        assertEquals("", P.normalizeDecimals(""))
    }

    @Test
    fun promptTeachesTheDecimalCommaAndTheCaloriesRule() {
        val prompt = BiaAnalysisService.SYSTEM_PROMPT
        assertTrue(prompt.contains("virgola"))
        assertTrue(prompt.contains("1,8 kg"))
        assertTrue(prompt.contains("caloriesAndMacros") || prompt.contains("record K"))
        assertTrue(prompt.contains("NON scrivere cifre"))
        assertTrue(prompt.contains("punti percentuali"))
    }
}
