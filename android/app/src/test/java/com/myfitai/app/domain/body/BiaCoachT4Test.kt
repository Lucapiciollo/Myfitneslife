package com.myfitai.app.domain.body

import com.myfitai.app.R
import com.myfitai.app.ai.AiContractRegistry
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiFailure
import com.myfitai.app.ai.AiFailureMessages
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRawResponse
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.body.BiaAnalysisPresentation as P
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        calories: String = "Il target abituale e coerente con l'obiettivo e le proteine restano ben distribuite.",
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
        contextText = "TARGET_ABITUALE_KCAL:tdee=2974,target=2528,p=179,c=292,f=72\n" +
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

    /**
     * T5: the negative example describes the mistake in words. Repeating the syntax of the real records
     * (`S|STABLE`, `Y|PROBABLY_POSITIVE`, `M|MONITOR`) taught the model to leave the B record out or to fill S badly.
     */
    @Test
    fun protocolAndPromptDescribeTheNegativeExampleWithoutRepeatingTheRecordSyntax() {
        val text = listOf(BiaAnalysisContract.schemaJson, BiaAnalysisService.SYSTEM_PROMPT).joinToString("\n")
        listOf("S|STABLE", "Y|PROBABLY_POSITIVE", "M|MONITOR", "ESEMPIO ERRATO", "wrong:").forEach {
            assertFalse("$it must not be repeated", text.contains(it))
        }
        assertTrue(BiaAnalysisService.SYSTEM_PROMPT.contains("maiuscolo"))
        assertTrue(BiaAnalysisService.SYSTEM_PROMPT.contains("almeno 3 parole"))
        // The B record is declared mandatory in both places.
        assertTrue(BiaAnalysisService.SYSTEM_PROMPT.contains("record B") && BiaAnalysisService.SYSTEM_PROMPT.contains("obbligatorio"))
        assertTrue(BiaAnalysisContract.schemaJson.contains("B record is mandatory"))
        // Reliability only as the last field of G.
        assertTrue(BiaAnalysisContract.schemaJson.contains("never inside the comment"))
    }

    // ---- (3) calories and macros are written by the app ------------------------------------------------------

    @Test
    fun validator_rejectsKcalAndGramFiguresInObjectiveAndCaloriesText() {
        // The observed case: 2974 is the TDEE, but the model called it "target abituale". The number is in the context
        // (NUMBER_NOT_IN_CONTEXT cannot catch it), the figure itself is not allowed.
        val observed = interpretation(calories = "Il target abituale 2974 kcal e coerente con l'obiettivo.")
        assertEquals("BIA_CALORIES_TEXT_HAS_FIGURES:caloriesAndMacros", error(observed))
        assertEquals("BIA_CALORIES_TEXT_HAS_FIGURES:caloriesAndMacros", error(interpretation(calories = "Proteine a 179 g al giorno.")))
        assertEquals("BIA_CALORIES_TEXT_HAS_FIGURES:objective", error(interpretation(objective = "Con 2528 kcal l'obiettivo e raggiungibile.")))
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
    fun caloriesSection_isWrittenByTheAppWithOneTargetAndTheTdeeNamedAsConsumption() {
        val text = BiaAnalysisReport.caloriesSection(calculation)

        val lines = text.lines()
        assertEquals("Target abituale: 2528 kcal, proteine 179 g, carboidrati 292 g, grassi 72 g", lines[0])
        assertEquals("Consumo abituale stimato (TDEE): 2974 kcal", lines[1])
        assertEquals(2, lines.size)
        // One target for every day: no "today" / rest-day / workout-day variant any more.
        assertFalse(text.contains("Oggi"))
        assertFalse(text.contains("operativo"))
        // The TDEE is never written as a target.
        assertFalse(text.contains("target 2974"))
        assertFalse(text.contains("Target abituale: 2974"))
    }

    @Test
    fun caloriesSection_missingValuesAreLeftOut() {
        assertEquals("", BiaAnalysisReport.caloriesSection(null))
        val onlyTarget = LocalCalculationEngine.Result(
            bmi = null, bmrKcal = null, tdeeKcal = null, targetKcal = 2528.1, proteinG = null, fatG = null, carbsG = null,
            waistHeightRatio = null, bmrMethod = null,
        )
        assertEquals("Target abituale: 2528 kcal", BiaAnalysisReport.caloriesSection(onlyTarget))
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

    // ===== T5: robust parsing of the BA2 answer, retry hint, manual retry, G comment ============================

    private val required = listOf(
        "S|Andamento stabile con grasso in lieve calo.", "C|Il trend resta nel rumore.", "F|?",
        "O|L'obiettivo e coerente con i dati.", "K|Il target e coerente con l'obiettivo.", "P|Ripeti la misura tra tre settimane.",
        "L|MEDIUM|Storico coerente.", "X|Ripeti la misura nelle stesse condizioni.", "X|Mantieni il piano attuale.",
        "X|Distribuisci le proteine nei pasti.", "N|?", "V|1|ok",
    )

    private fun dataOf(lines: List<String>): String = JSONObject().put("data", lines.joinToString("\n")).toString()

    private fun parsed(head: List<String>, body: List<String> = required) = BiaAnalysisContract.parse(dataOf(listOf("BA2") + head + body))

    private fun parseError(head: List<String>, body: List<String> = required): String? =
        runCatching { parsed(head, body) }.exceptionOrNull()?.message

    @Test
    fun parser_acceptsTheClassificationRecordWithCaseSpacesQuotesAndEmphasis() {
        listOf("B|STABLE", "b|stable", " B | STABLE ", "B |Stabile", "B|\"STABLE\"", "**B|STABLE**", "- B|STABLE", "B|Esito: stable (stabile)").forEach { line ->
            assertEquals(line, "STABLE", parsed(listOf(line)).classification)
        }
        assertEquals("PROBABLY_POSITIVE", parsed(listOf("B|Probabilmente positivo")).classification)
        assertEquals("INSUFFICIENT_DATA", parsed(listOf("b | dati insufficienti ")).classification)
    }

    @Test
    fun parser_tolerantTagsAndQuotesDoNotChangeTheTextOfTheOtherRecords() {
        val result = parsed(listOf(" b |stable"), listOf("s |  \"Andamento stabile con grasso in lieve calo.\"") + required.drop(1))
        assertEquals("Andamento stabile con grasso in lieve calo.", result.verdict)
        assertEquals(3, result.actions.size)
    }

    @Test
    fun parser_recoversTheClassificationFromTheHeaderLine() {
        val oneLine = "BA2|STABLE|S|Andamento stabile con grasso in lieve calo.|C|Il trend resta nel rumore.|F|?|" +
            "O|L'obiettivo e coerente con i dati.|K|Il target e coerente.|P|Ripeti la misura.|L|MEDIUM|Storico coerente.|" +
            "X|Ripeti la misura ora.|X|Mantieni il piano attuale.|X|Distribuisci le proteine.|N|?|V|1|ok"
        val result = BiaAnalysisContract.parse(JSONObject().put("data", oneLine).toString())
        assertEquals("STABLE", result.classification)
        assertEquals("Andamento stabile con grasso in lieve calo.", result.verdict)

        assertEquals("STABLE", BiaAnalysisContract.parse(dataOf(listOf("BA2 STABLE") + required)).classification)
        assertEquals("PROBABLY_POSITIVE", parsed(listOf("Probabilmente positivo")).classification)
        assertEquals("MONITOR", parsed(listOf("Classificazione: da monitorare")).classification)
        assertEquals("NEGATIVE", parsed(listOf("classification = NEGATIVE")).classification)
    }

    @Test
    fun parser_usesAnEnumWrittenInTheVerdictOnlyWhenTheBRecordIsMissing_andTheValidatorStillRejectsIt() {
        val result = parsed(emptyList(), listOf("S|STABLE") + required.drop(1))
        assertEquals("STABLE", result.classification)
        assertEquals("BIA_VERDICT_EQUALS_CLASSIFICATION", BiaAnalysisContract.validateBusiness(result).exceptionOrNull()?.message)
        // With a real B record the verdict never decides the classification.
        assertEquals("MONITOR", parsed(listOf("B|MONITOR"), listOf("S|STABLE") + required.drop(1)).classification)
    }

    @Test
    fun parser_reportsAMissingClassificationAsSuchNeverAsAnInvalidRecord() {
        assertEquals("BIA_CLASSIFICATION_MISSING", parseError(emptyList()))
        assertEquals("BIA_CLASSIFICATION_MISSING", parseError(listOf("B|")))
        assertEquals("BIA_CLASSIFICATION_MISSING", parseError(listOf("Andamento generale")))
        // Present but unreadable stays a different, explicit code.
        assertEquals("BIA_CLASSIFICATION_INVALID", BiaAnalysisContract.validateBusiness(parsed(listOf("B|?"))).exceptionOrNull()?.message)
        // Two classifications are still ambiguous.
        assertEquals("BA2_B_INVALID", parseError(listOf("B|STABLE", "B|MONITOR")))
    }

    // ---- G records: the comment never carries the reliability ---------------------------------------------------

    private fun scenarios(vararg g: String) = parsed(listOf("B|STABLE"), required + g.toList()).scenarios

    @Test
    fun scenarioRecord_withSixSevenAndEightFields() {
        // 6 fields: no reliability, the whole field is the comment.
        val six = scenarios("G|S1|?|?|?|Ipotesi prudente sul corpo").single()
        assertEquals("Ipotesi prudente sul corpo", six.assumption)
        assertEquals("?", six.reliability)

        // 7 fields: the observed device case. `MODERATE` is the reliability, not part of the comment.
        val seven = scenarios("G|S1|?|?|?|Ipotesi prudente sul corpo|MODERATE").single()
        assertEquals("Ipotesi prudente sul corpo", seven.assumption)
        assertEquals("MEDIUM", seven.reliability)
        assertEquals("HIGH", scenarios("G|S2|?|?|?|Ipotesi sul percorso|alta").single().reliability)
        assertEquals("LOW", scenarios("G|S2|?|?|?|Ipotesi sul percorso|BASSA|").single().reliability)

        // 8 fields: a stray separator inside the comment is kept, the reliability is still the last field.
        val eight = scenarios("G|S1|?|?|?|Ipotesi | con stima|MEDIUM").single()
        assertEquals("Ipotesi | con stima", eight.assumption)
        assertEquals("MEDIUM", eight.reliability)

        // Unknown enum-shaped tail: not text, reliability unknown.
        val unknown = scenarios("G|S1|?|?|?|Ipotesi prudente sul corpo|VERY_HIGH").single()
        assertEquals("Ipotesi prudente sul corpo", unknown.assumption)
        assertEquals("?", unknown.reliability)

        // An empty comment is "?" (the app then writes its own), never a parse failure.
        assertEquals("?", scenarios("G|S1|?|?|?||MEDIUM").single().assumption)
        // A trailing separator before the reliability is not part of the comment.
        assertEquals("Ipotesi prudente", scenarios("G|S1|?|?|?|Ipotesi prudente||MEDIUM").single().assumption)
    }

    @Test
    fun scenarioComment_mayMentionTheTokenInsideTheSentence() {
        val scenario = scenarios("G|S1|?|?|?|Con affidabilità alta il corpo risponde|LOW").single()
        assertEquals("Con affidabilità alta il corpo risponde", scenario.assumption)
        assertEquals("LOW", scenario.reliability)
        val valid = parsed(listOf("B|STABLE"), required + "G|S1|?|?|?|Ipotesi su MODERATE variazioni del corpo|LOW")
        assertTrue(BiaAnalysisContract.validateBusiness(valid).isSuccess)
    }

    @Test
    fun validator_rejectsACommentEndingWithAnEnumValue() {
        // 8 fields: `MODERATE` stays at the end of the comment, the last field is the reliability.
        val eight = parsed(listOf("B|STABLE"), required + "G|S1|?|?|?|Ipotesi prudente sul corpo|MODERATE|MEDIUM")
        assertEquals("Ipotesi prudente sul corpo|MODERATE", eight.scenarios.single().assumption)
        assertEquals("BIA_TEXT_ENUM_TAIL:scenarios", BiaAnalysisContract.validateBusiness(eight).exceptionOrNull()?.message)

        // Six fields with the value after a space, and a lone trailing separator.
        assertEquals(
            "BIA_TEXT_ENUM_TAIL:scenarios",
            BiaAnalysisContract.validateBusiness(parsed(listOf("B|STABLE"), required + "G|S1|?|?|?|Ipotesi sul corpo MODERATE")).exceptionOrNull()?.message,
        )
        val scenario = BiaAnalysisContract.Scenario("Scenario S1", "?", "?", "?", "Ipotesi sul corpo|", "?")
        val trailing = parsed(listOf("B|STABLE")).copy(scenarios = listOf(scenario))
        assertEquals("BIA_TEXT_ENUM_TAIL:scenarios", BiaAnalysisContract.validateBusiness(trailing).exceptionOrNull()?.message)

        // Same rule on the comparison commentary (R with 8 fields).
        val comparison = parsed(listOf("B|STABLE"), required + "R|Peso|90 kg|89 kg|-1 kg|Calo compatibile|LOW|MEDIUM")
        assertEquals("BIA_TEXT_ENUM_TAIL:comparisons", BiaAnalysisContract.validateBusiness(comparison).exceptionOrNull()?.message)
        assertEquals("MEDIUM", parsed(listOf("B|STABLE"), required + "R|Peso|90 kg|89 kg|-1 kg|Calo compatibile|MODERATE").comparisons.single().reliability)
    }

    @Test
    fun appScenarios_keepTheModelCommentWithoutTheReliabilityToken() {
        val model = parsed(
            listOf("B|STABLE"),
            required + listOf(
                "G|S1|?|?|?|Ipotesi prudente sul corpo|MODERATE",
                "G|S2|?|?|?|Ipotesi sul percorso, da verificare|MEDIUM",
            ),
        )
        val applied = BiaAnalysisContract.withAppScenarios(model, listOf(BiaScenarioCalculator.Scenario("S1", 18.0, 85.0, 2.0), BiaScenarioCalculator.Scenario("S2", 16.0, 83.0, 4.0)))
        applied.scenarios.forEach {
            assertFalse(it.assumption, it.assumption.contains('|'))
            assertFalse(it.assumption, it.assumption.contains("MODERATE"))
        }
        assertEquals("Ipotesi prudente sul corpo", applied.scenarios[0].assumption)
        assertEquals("MEDIUM", applied.scenarios[0].reliability)
    }

    @Test
    fun savedScenarioCommentsAreCleanedOnDisplay() {
        // Payloads saved before T5 carry the tail: the stored format is not touched, the text shown is clean.
        assertEquals("Ipotesi prudente sul corpo", P.scenarioComment("Ipotesi prudente sul corpo|MODERATE"))
        assertEquals("Ipotesi prudente sul corpo", P.scenarioComment("Ipotesi prudente sul corpo | MEDIUM |"))
        assertEquals("Ipotesi prudente sul corpo", P.scenarioComment("Ipotesi prudente sul corpo|"))
        assertEquals("Massa magra stabile | stima prudente", P.scenarioComment("Massa magra stabile | stima prudente"))
        assertEquals("Con affidabilità alta il corpo risponde", P.scenarioComment("Con affidabilità alta il corpo risponde"))
        assertEquals("", P.scenarioComment("MODERATE"))
        assertEquals("", P.scenarioComment(""))
    }

    // ---- business retry names the missing record -------------------------------------------------------------

    private class ScriptedProvider(private val answers: List<String>) : com.myfitai.app.ai.AiProvider {
        override val type = AiProviderType.OPENAI
        val prompts = mutableListOf<String>()
        override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
            prompts += request.userPrompt
            return AiRawResponse(type, "fake", answers[prompts.size - 1])
        }
    }

    private fun businessOf(json: String): Result<Unit> =
        runCatching { BiaAnalysisContract.validateBusiness(BiaAnalysisContract.parse(json)).getOrThrow() }

    @Test
    fun businessRetry_forAMissingClassificationNamesTheRecordAndTheAllowedValues_withoutModelText() {
        val marker = "ZZ-MODEL-TEXT-ZZ"
        val withoutB = dataOf(listOf("BA2") + listOf("S|Andamento $marker in lieve calo.") + required.drop(1))
        val provider = ScriptedProvider(listOf(withoutB, dataOf(listOf("BA2", "B|STABLE") + required)))

        val response = runBlocking {
            AiExecutionService(diagnostics = {}).execute(
                provider,
                AiStructuredRequest("system", "user", BiaAnalysisContract.SCHEMA_NAME, BiaAnalysisContract.schemaJson),
                maxSchemaRetries = 1,
                businessValidator = ::businessOf,
            )
        }

        assertEquals(2, provider.prompts.size)
        assertTrue(response.jsonText.contains("B|STABLE"))
        val fix = provider.prompts[1].substringAfter("\nFIX:")
        assertTrue(fix, fix.startsWith("BIA_CLASSIFICATION_MISSING"))
        assertTrue(fix, fix.contains("B|<CLASSIFICATION>"))
        assertTrue(fix, fix.contains("POSITIVE, PROBABLY_POSITIVE, STABLE, MONITOR, NEGATIVE, INSUFFICIENT_DATA"))
        assertTrue(fix, fix.contains("right after BA2"))
        // The generic closing of the hint is unchanged, and nothing written by the model is echoed back.
        assertTrue(fix, fix.contains("Return exactly one JSON envelope with one data property. The data value must begin with BA2"))
        assertFalse(provider.prompts[1], provider.prompts[1].contains(marker))
    }

    @Test
    fun fixHints_existOnlyForTheCoachContractAndNeverCarryModelText() {
        val coach = AiContractRegistry.require(BiaAnalysisContract.SCHEMA_NAME)
        listOf(
            "BIA_CLASSIFICATION_MISSING", "BIA_CLASSIFICATION_INVALID", "BIA_VERDICT_MISSING", "BIA_VERDICT_EQUALS_CLASSIFICATION",
            "BIA_PLACEHOLDER_TEXT:positives", "BIA_TEXT_TOO_SHORT:monitor", "BIA_TEXT_ENUM_TAIL:scenarios", "THREE_ACTIONS_REQUIRED",
        ).forEach { reason -> assertNotNull(reason, coach.fixHint(reason)) }
        assertNull(coach.fixHint("SOMETHING_UNKNOWN"))
        // The hint is looked up by the stable code only: a reason carrying other text finds the same hint.
        assertEquals(coach.fixHint("BIA_TEXT_ENUM_TAIL"), coach.fixHint("BIA_TEXT_ENUM_TAIL:scenarios"))
        // Other contracts keep the old retry text.
        assertNull(AiContractRegistry.require("myfitai_meal_alternatives_pipe_v1").fixHint("MA_TITLE"))
    }

    // ---- final failure: clear message, manual retry only for the BIA analysis -----------------------------------

    @Test
    fun biaAnalysis_offersAManualRetryForARejectedAnswerWithoutChangingTheGlobalRule() {
        val retry = AiFailureMessages.resolveForBiaAnalysis("BIA_CLASSIFICATION_MISSING")
        assertTrue(retry.canRetry)
        assertEquals(R.string.ai_failure_rejected, retry.messageRes)
        assertEquals("BIA_CLASSIFICATION_MISSING", retry.technicalCode)
        assertEquals(AiFailureMessages.Action.NONE, retry.action)
        listOf("BIA_PLACEHOLDER_TEXT", "BIA_TEXT_TOO_SHORT", "BIA_TEXT_ENUM_TAIL", "BIA_VERDICT_EQUALS_CLASSIFICATION", "NUMBER_NOT_IN_CONTEXT", "BIA_CALORIES_TEXT_HAS_FIGURES")
            .forEach { code ->
                assertEquals(code, code, AiFailure.stableBusinessCode("$code:positives"))
                assertTrue(code, AiFailureMessages.resolveForBiaAnalysis(code).canRetry)
            }

        // The global classification is untouched: the generic resolver and AiFailure still say "not retryable".
        assertFalse(AiFailureMessages.resolve("BIA_CLASSIFICATION_MISSING").canRetry)
        assertFalse(AiFailure.BusinessRuleViolated("BIA_CLASSIFICATION_MISSING").retryable)
        // Everything that is not a rejected answer keeps its normal behaviour.
        assertFalse(AiFailureMessages.resolveForBiaAnalysis(AiFailure.Quota.userMessage).canRetry)
        assertEquals(AiFailureMessages.Action.OPEN_AI_SETTINGS, AiFailureMessages.resolveForBiaAnalysis(AiFailure.Quota.userMessage).action)
        assertTrue(AiFailureMessages.resolveForBiaAnalysis(AiFailure.Network.userMessage).canRetry)
        assertFalse(AiFailureMessages.resolveForBiaAnalysis("Analisi BIA non disponibile").canRetry)
        assertFalse(AiFailureMessages.resolveForBiaAnalysis(null).canRetry)
    }
}
