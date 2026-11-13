package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.body.BiaScenarioCalculator.Composition
import com.myfitai.app.domain.body.BiaScenarioCalculator.FatStatus
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.OperationalNutritionMetrics
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3 of the BIA Coach: labelled targets, scenarios calculated by the app, NUMBER_NOT_IN_CONTEXT,
 * compatibility of saved results and presentation rules.
 */
class BiaCoachT3Test {
    // ---- BiaScenarioCalculator -------------------------------------------------------------------------

    @Test
    fun womanOf62_scenariosKeepLeanMassAndReachTheTargetBodyFat() {
        // Woman, 62 years, 164 cm: 68.0 kg, 34 % fat -> 23.12 kg fat, 44.88 kg non-fat.
        val scenarios = BiaScenarioCalculator.scenarios(Composition(weightKg = 68.0, bodyFatPercent = 34.0, fatMassKg = 23.12, leanMassKg = 44.9), "Femmina")

        assertEquals(listOf("S1", "S2", "S3"), scenarios.map { it.id })
        assertEquals(listOf(32.0, 30.0, 28.0), scenarios.map { it.targetBodyFatPercent })
        assertEquals(listOf(66.0, 64.1, 62.3), scenarios.map { it.targetWeightKg })
        assertEquals(listOf(2.0, 3.9, 5.7), scenarios.map { it.fatToLoseKg })
        // Weight change is the opposite of the fat to lose; every figure has one decimal.
        assertEquals(-2.0, scenarios.first().weightChangeKg, 0.0)
        scenarios.forEach { assertEquals(it.targetWeightKg, BiaScenarioCalculator.round1(it.targetWeightKg), 0.0) }
        // The lean mass is really kept: lean / (1 - target) reproduces the target weight within rounding.
        scenarios.forEach { assertEquals(44.88 / (1 - it.targetBodyFatPercent / 100), it.targetWeightKg, 0.051) }
    }

    @Test
    fun man_scenariosUseHisOwnFloorAndRounding() {
        val scenarios = BiaScenarioCalculator.scenarios(Composition(weightKg = 90.0, bodyFatPercent = 25.0), "Maschio")

        assertEquals(listOf(23.0, 21.0, 19.0), scenarios.map { it.targetBodyFatPercent })
        assertEquals(listOf(87.7, 85.4, 83.3), scenarios.map { it.targetWeightKg })
        assertEquals(listOf(2.3, 4.6, 6.7), scenarios.map { it.fatToLoseKg })
    }

    @Test
    fun scenariosNeverGoBelowTheRealisticFloorOrAboveTheCurrentBodyFat() {
        val woman24 = BiaScenarioCalculator.scenarios(Composition(weightKg = 60.0, bodyFatPercent = 24.0), "Femmina")
        assertEquals(listOf(22.0, 20.0), woman24.map { it.targetBodyFatPercent })
        assertEquals(listOf("S1", "S2"), woman24.map { it.id })
        // Already lean: no realistic target below the floor.
        assertTrue(BiaScenarioCalculator.scenarios(Composition(weightKg = 60.0, bodyFatPercent = 20.5), "Femmina").isEmpty())
        assertTrue(BiaScenarioCalculator.scenarios(Composition(weightKg = 75.0, bodyFatPercent = 11.0), "Maschio").isEmpty())
        // Unknown sex uses the widest floor.
        assertEquals(listOf(18.0, 16.0, 14.0), BiaScenarioCalculator.scenarios(Composition(weightKg = 80.0, bodyFatPercent = 20.0), null).map { it.targetBodyFatPercent })
    }

    @Test
    fun missingDataProducesNoScenarioInsteadOfAGuess() {
        assertTrue(BiaScenarioCalculator.scenarios(Composition(), "Femmina").isEmpty())
        assertTrue(BiaScenarioCalculator.scenarios(Composition(bodyFatPercent = 30.0), "Femmina").isEmpty())
        assertTrue(BiaScenarioCalculator.scenarios(Composition(weightKg = 70.0), "Femmina").isEmpty())
        // Fat mass bigger than the weight is incoherent data.
        assertTrue(BiaScenarioCalculator.scenarios(Composition(weightKg = 60.0, fatMassKg = 65.0), "Femmina").isEmpty())
        // Fat and lean mass are enough to derive the weight.
        val derived = BiaScenarioCalculator.scenarios(Composition(fatMassKg = 20.0, leanMassKg = 50.0), "Femmina")
        assertEquals(listOf(27.0, 25.0, 23.0), derived.map { it.targetBodyFatPercent })
    }

    @Test
    fun fatChange_withinTheNoiseThresholdsClaimsNothing() {
        val change = BiaScenarioCalculator.fatChange(
            Composition(weightKg = 90.0, bodyFatPercent = 20.0),
            Composition(weightKg = 89.5, bodyFatPercent = 19.6),
        )

        assertEquals(FatStatus.WITHIN_NOISE, change.status)
        assertNull(change.estimatedFatLostKg)
        assertEquals(-0.5, change.weightDeltaKg!!, 1e-9)
    }

    @Test
    fun fatChange_aboveNoiseIsAnEstimateOfFatLostAndDoublesWhenDegraded() {
        val previous = Composition(weightKg = 90.0, bodyFatPercent = 20.0)
        val current = Composition(weightKg = 88.5, bodyFatPercent = 18.2)

        val change = BiaScenarioCalculator.fatChange(previous, current)
        assertEquals(FatStatus.FAT_DOWN, change.status)
        assertEquals(18.0 - 88.5 * 0.182, change.estimatedFatLostKg!!, 1e-9)
        assertEquals(-1.8, change.bodyFatDeltaPoints!!, 1e-9)
        assertEquals(-1.5, change.weightDeltaKg!!, 1e-9)

        // Different conditions: thresholds doubled (1.6 kg, 2.0 points), the same change is no longer reliable.
        assertEquals(FatStatus.WITHIN_NOISE, BiaScenarioCalculator.fatChange(previous, current, degraded = true).status)
        assertEquals(FatStatus.FAT_UP, BiaScenarioCalculator.fatChange(current, previous).status)
        assertNull(BiaScenarioCalculator.fatChange(current, previous).estimatedFatLostKg)
    }

    @Test
    fun fatChange_withoutBothFatMassesIsNotAvailable() {
        assertEquals(FatStatus.NOT_AVAILABLE, BiaScenarioCalculator.fatChange(Composition(weightKg = 90.0), Composition(weightKg = 89.0, bodyFatPercent = 19.0)).status)
        assertEquals(FatStatus.NOT_AVAILABLE, BiaScenarioCalculator.fatChange(Composition(), Composition()).status)
        // Fat mass in kg alone (no percentage) is compared with the weight threshold.
        assertEquals(FatStatus.FAT_DOWN, BiaScenarioCalculator.fatChange(Composition(fatMassKg = 20.0), Composition(fatMassKg = 18.9)).status)
        assertEquals(FatStatus.WITHIN_NOISE, BiaScenarioCalculator.fatChange(Composition(fatMassKg = 20.0), Composition(fatMassKg = 19.5)).status)
    }

    // ---- BiaNumberGuard / NUMBER_NOT_IN_CONTEXT -----------------------------------------------------------

    private val sampleContext = "DATA_ANALISI:2026-11-12\nBIA_CORRENTE:date=2026-09-27,weightKg=89.5,bodyFatPercent=19.9,leanMassKg=71.7\n" +
        "TARGET_ABITUALE_KCAL:tdee=2974,target=2528,p=179,c=292,f=72\nTREND_BIA:weightKg n=3 giorni=20 delta=-1.2 soglia=0.8\n" +
        "STORICO_BIA:abdomen=80,duration=45"

    private fun unsupported(text: String) = BiaNumberGuard.firstUnsupported(listOf(text), sampleContext)?.toString()

    @Test
    fun numberGuard_acceptsFiguresPresentInTheContextWithTheirRounding() {
        assertNull(unsupported("Il peso e 89,5 kg e il grasso 19,9 %"))
        assertNull(unsupported("Il target abituale e 2.528 kcal e 2528 kcal"))
        assertNull(unsupported("179 g di proteine, 292 g di carboidrati"))
        // Rounded to the unit: 89.5 -> 90 and 19.9 -> 20 are rounding, not new numbers.
        assertNull(unsupported("circa 90 kg e 20 %"))
        // A range is checked on both ends.
        assertNull(unsupported("tra 71,7-89,5 kg"))
        assertNull(unsupported("calo di 1,2 kg"))
    }

    @Test
    fun numberGuard_rejectsNumbersNotInTheContext() {
        assertEquals("4.2 kg", unsupported("Potresti perdere 4,2 kg"))
        // The original 2527 kcal case: close to the habitual target (2528) but written at a different precision.
        assertEquals("2527 kcal", unsupported("Calorie target 2527 kcal"))
        assertEquals("89.6 kg", unsupported("Peso 89,6 kg"))
        assertEquals("25 %", unsupported("Grasso al 25 %"))
        assertEquals("200 g", unsupported("Servono 200 g di proteine"))
        // Second end of a range.
        assertEquals("95 kg", unsupported("tra 89,5-95 kg"))
    }

    @Test
    fun numberGuard_ignoresHarmlessNumbersAndOtherUnits() {
        assertNull(unsupported("Tre azioni, 1 settimana, 3 azioni e 2 giorni"))
        assertNull(unsupported("Ripeti tra 3 settimane la misura"))
        assertNull(unsupported("Mai sotto lo 0 kg o al 100 %"))
        // "g" must be a unit, not the first letter of a word.
        assertNull(unsupported("30 grassi, 12 gruppi"))
        assertTrue(BiaNumberGuard.quantities("30 grassi").isEmpty())
        // Without any number there is nothing to check.
        assertNull(unsupported("Nessuna cifra qui"))
    }

    @Test
    fun numberGuard_datesAndCountersOfTheContextDoNotAuthoriseNumbers() {
        // 2026-11-12 and giorni=20 / n=3 are bookkeeping, not data a model can quote.
        val counters = "DATA_ANALISI:2026-11-12\nTREND_BIA:weightKg n=3 giorni=20 delta=-1.2 soglia=0.8"
        fun bookkeeping(text: String) = BiaNumberGuard.firstUnsupported(listOf(text), counters)?.toString()
        assertEquals("12 kg", bookkeeping("Hai perso 12 kg"))
        assertEquals("20 kg", bookkeeping("Hai perso 20 kg"))
        assertEquals("3 kg", bookkeeping("Hai perso 3 kg"))
        assertNull(bookkeeping("Variazione di 1,2 kg"))
        // A field that merely ends with n (abdomen, duration) is still data.
        assertTrue(BiaNumberGuard.contextNumbers(sampleContext).containsAll(listOf(80.0, 45.0)))
    }

    private fun interpretation(whatIsHappening: String = "Trend da monitorare.", fatLoss: String = "?", calories: String = "?") =
        BiaAnalysisContract.parse(
            JSONObject().put(
                "data",
                listOf(
                    "BA2", "B|STABLE", "S|Stabile.", "C|$whatIsHappening", "F|$fatLoss", "O|?", "K|$calories", "P|Ripeti la misura.",
                    "L|MEDIUM|Storico coerente.", "X|Una", "X|Due", "X|Tre", "N|?", "V|1|ok",
                ).joinToString("\n"),
            ).toString(),
        )

    @Test
    fun validator_rejectsANumberOutsideTheContextWithTheBusinessCode() {
        val validation = BiaAnalysisContract.ValidationContext(contextText = sampleContext)

        val error = BiaAnalysisContract.validateBusinessWith(interpretation(calories = "Calorie target 2527 kcal."), validation).exceptionOrNull()
        assertTrue(error?.message, error?.message?.startsWith("NUMBER_NOT_IN_CONTEXT") == true)
        assertEquals("NUMBER_NOT_IN_CONTEXT:2527 kcal", error?.message)
        val fat = BiaAnalysisContract.validateBusinessWith(interpretation(fatLoss = "Circa 4,2 kg di grasso persi."), validation).exceptionOrNull()
        assertEquals("NUMBER_NOT_IN_CONTEXT:4.2 kg", fat?.message)
    }

    @Test
    fun validator_acceptsTextThatOnlyRepeatsContextFigures() {
        val validation = BiaAnalysisContract.ValidationContext(contextText = sampleContext)

        val ok = interpretation(
            whatIsHappening = "Il peso e 89,5 kg, la variazione di 1,2 kg e entro 3 settimane da confermare.",
            fatLoss = "Stima prudente, soggetta al rumore. Il target abituale e 2528 kcal con 179 g di proteine.",
            calories = "Il target e coerente con l'obiettivo.",
        )
        assertTrue(BiaAnalysisContract.validateBusinessWith(ok, validation).exceptionOrNull()?.message ?: "", BiaAnalysisContract.validateBusinessWith(ok, validation).isSuccess)
    }

    @Test
    fun validator_withoutContextKeepsOldBehaviourForSavedAndLegacyResults() {
        val legacy = interpretation(calories = "Calorie target 2527 kcal.", fatLoss = "Circa 4,2 kg.")
        assertTrue(BiaAnalysisContract.validateBusiness(legacy).isSuccess)
        assertTrue(BiaAnalysisContract.validateBusinessWith(legacy, BiaAnalysisContract.ValidationContext()).isSuccess)
    }

    // ---- App scenarios replace the model numbers; compatibility with old results -------------------------------

    private val womanScenarios = BiaScenarioCalculator.scenarios(Composition(weightKg = 68.0, bodyFatPercent = 34.0, fatMassKg = 23.12), "Femmina")

    private fun withScenarioRecords(vararg records: String) = BiaAnalysisContract.parse(
        JSONObject().put(
            "data",
            (
                listOf("BA2", "B|STABLE", "S|Stabile.", "C|Trend.", "F|?", "O|?", "K|?", "P|Ripeti.", "L|MEDIUM|Storico coerente.") +
                    records + listOf("X|Una", "X|Due", "X|Tre", "N|?", "V|1|ok")
                ).joinToString("\n"),
        ).toString(),
    )

    @Test
    fun appScenarios_replaceModelNumbersAndKeepTheModelCommentaryById() {
        val model = withScenarioRecords(
            "G|S1|20%|40 kg|30 kg|Obiettivo graduale, da verificare con una nuova misura.|MEDIUM",
            "G|S2|?|?|?|Piu impegnativo.|LOW",
        )

        val applied = BiaAnalysisContract.withAppScenarios(model, womanScenarios)

        assertEquals(3, applied.scenarios.size)
        val first = applied.scenarios[0]
        assertEquals("Scenario S1", first.label)
        assertEquals("32 %", first.targetBodyFat)
        assertEquals("66,0 kg", first.targetWeight)
        assertEquals("2,0 kg", first.fatToLose)
        assertEquals("Obiettivo graduale, da verificare con una nuova misura.", first.assumption)
        assertEquals("MEDIUM", first.reliability)
        assertEquals("LOW", applied.scenarios[1].reliability)
        // No model comment for S3: the app writes the assumption, the model number never survives.
        assertEquals(BiaAnalysisContract.DEFAULT_SCENARIO_ASSUMPTION, applied.scenarios[2].assumption)
        assertEquals("?", applied.scenarios[2].reliability)
        assertTrue(applied.scenarios.none { it.targetWeight.contains("40") || it.fatToLose.contains("30") })
    }

    @Test
    fun appScenarios_withoutAppNumbersTheModelScenariosAreDropped() {
        val model = withScenarioRecords("G|Obiettivo|20%|86 kg|3 kg|Ipotesi libera.|MEDIUM")
        assertTrue(BiaAnalysisContract.withAppScenarios(model, emptyList()).scenarios.isEmpty())
        // A reference to an unknown id does not bring in the model numbers either.
        assertEquals("?", BiaAnalysisContract.withAppScenarios(model, womanScenarios).scenarios.first().reliability)
        assertEquals(BiaAnalysisContract.DEFAULT_SCENARIO_ASSUMPTION, BiaAnalysisContract.withAppScenarios(model, womanScenarios).scenarios.first().assumption)
    }

    @Test
    fun oldSavedBa2ResultsStillParseAndValidateWithTheOldFormat() {
        // Format used before T3: the model writes every G number itself and the old fixed safety note is present.
        val old = BiaAnalysisContract.parse(
            JSONObject().put(
                "data",
                "BA2\nB|PROBABLY_POSITIVE\nS|Andamento favorevole.\nC|Grasso in calo.\nF|Circa 3 kg in uno scenario moderato.\nO|Coerente.\nK|Calorie circa 2100.\n" +
                    "P|Ripeti tra 3 settimane.\nL|MEDIUM|Due rilevazioni comparabili.\nG|Primo obiettivo|20%|86 kg|3 kg|Massa magra stabile.|MEDIUM\n" +
                    "X|Una\nX|Due\nX|Tre\nD|Analisi informativa, non diagnosi medica\nN|?\nV|1|ok",
            ).toString(),
        )
        assertEquals("Primo obiettivo", old.scenarios.single().label)
        assertTrue(BiaAnalysisContract.validateBusiness(old).isSuccess)
        // The same text is also valid with the new context-aware validator as long as its numbers are in the context.
        assertTrue(
            BiaAnalysisContract.validateBusinessWith(old, BiaAnalysisContract.ValidationContext(contextText = "grasso=3 kg 2100 kcal")).isSuccess,
        )
    }

    @Test
    fun payloadJson_keepsEveryLegacyKeyAndStoresTheSafetyNoteAsWritten() {
        val parsed = interpretation()
        val result = BiaAnalysisService.Result(BiaAnalysisContract.withAppScenarios(parsed, womanScenarios), "GEMINI", "m")

        val payload = JSONObject(BiaAnalysisAiJobHandler.payloadJson(result))

        listOf(
            "classification", "verdict", "whatIsHappening", "fatLoss", "objective", "caloriesAndMacros", "nextCheck", "reliability",
            "reliabilityReason", "comparisons", "historical", "scenarios", "positives", "monitor", "actions", "safetyNote", "question",
            "agentValidation", "provider", "model",
        ).forEach { assertTrue(it, payload.has(it)) }
        assertEquals("", payload.getString("safetyNote"))
        assertEquals(false, payload.getBoolean("safetyFlagActive"))
        assertEquals(3, payload.getJSONArray("scenarios").length())
        assertEquals("32 %", payload.getJSONArray("scenarios").getJSONObject(0).getString("targetBodyFat"))
        assertEquals(true, JSONObject(BiaAnalysisAiJobHandler.payloadJson(result, safetyFlagActive = true)).getBoolean("safetyFlagActive"))
    }

    // ---- Service: validator in the loop, scenarios replaced -------------------------------------------------

    private class FakeGateway(private val data: String) : AiRuntimeGateway {
        var prompts = 0
        override suspend fun execute(
            request: AiStructuredRequest,
            maxSchemaRetries: Int,
            businessValidator: (String) -> Result<Unit>,
        ): AiExecutionService.ValidatedResponse {
            prompts++
            val json = JSONObject().put("data", data).toString()
            businessValidator(json).getOrThrow()
            return AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "fake", json)
        }
    }

    private fun serviceData(calories: String, scenarioRecord: String) = listOf(
        "BA2", "B|STABLE", "S|Stabile.", "C|Peso 68,0 kg e grasso 34 %.", "F|Stima non affidabile, entro il rumore BIA.", "O|?", "K|$calories",
        "P|Ripeti la misura tra 3 settimane.", "L|MEDIUM|Storico coerente.", scenarioRecord, "X|Una", "X|Due", "X|Tre", "N|?", "V|1|ok",
    ).joinToString("\n")

    @Test
    fun service_rejectsInventedNumbersAndReplacesScenarioNumbers() = runBlocking {
        val prompt = "BIA_CORRENTE:weightKg=68.0,bodyFatPercent=34.0\nTARGET_ABITUALE_KCAL:tdee=2974,target=2528\n" +
            "SCENARI_APP:S1 grasso_target=32%,peso_obiettivo=66.0kg,grasso_da_perdere=2.0kg"

        val good = FakeGateway(serviceData("Il target e coerente con l'obiettivo.", "G|S1|99%|9 kg|9 kg|Commento sul primo scenario.|MEDIUM"))
        val result = BiaAnalysisService(good).analyze(BiaAnalysisService.Report(prompt, 3, appScenarios = womanScenarios))
        assertEquals("66,0 kg", result.interpretation.scenarios.first().targetWeight)
        assertEquals("Commento sul primo scenario.", result.interpretation.scenarios.first().assumption)

        val bad = FakeGateway(serviceData("Target di 2527 kcal.", "G|S1|?|?|?|Commento.|MEDIUM"))
        val failure = runCatching { BiaAnalysisService(bad).analyze(BiaAnalysisService.Report(prompt, 3, appScenarios = womanScenarios)) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure?.message, failure?.message?.startsWith("NUMBER_NOT_IN_CONTEXT") == true)
    }

    // ---- Context: labelled targets, scenarios, fat estimate -------------------------------------------------

    private val today = LocalDate.of(2026, 11, 12)
    private val day = 86_400_000L

    private fun profile() = UserProfileEntity(
        id = 1L, name = "Test", birthDateEpochDay = LocalDate.of(1964, 3, 10).toEpochDay(), heightCm = 164f, currentWeightKg = 68f,
        goal = "Ricomposizione", activityLevel = "Sedentario", wakeTimeMinutes = 420, sleepTimeMinutes = 1380, dietaryPreferencesJson = null,
        createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L, biologicalSex = "Femmina",
    )

    private fun reading(id: Long, dayIndex: Int, weight: Float?, fat: Float?, fatMass: Float?, lean: Float? = null) = BiaMeasurementEntity(
        id = id, profileId = 1L, measuredAtEpochMillis = 1_700_000_000_000L + dayIndex * day, weightKg = weight, bodyFatPercent = fat,
        visceralFatLevel = null, muscleMassKg = null, skeletalMuscleKg = null, bodyWaterPercent = null, bmrKcal = null,
        fasting = true, justWokeUp = false, afterBathroom = true, noRecentWorkout = false, fatMassKg = fatMass, leanMassKg = lean,
    )

    private val calculation = LocalCalculationEngine.Result(
        bmi = 25.3, bmrKcal = 1789.4, tdeeKcal = 2974.2, targetKcal = 2528.1, proteinG = 179.0, fatG = 72.0, carbsG = 292.0,
        waistHeightRatio = null, bmrMethod = "MIFFLIN_ST_JEOR",
    )

    private fun buildCoachContext(all: List<BiaMeasurementEntity>) = BiaAnalysisReport.coachContext(
        profile = profile(), today = today, report = BiaAnalysisReport.build(all)!!, bodyHistory = emptyList(), workoutHistory = emptyList(),
        planDays = emptyList(), calculation = calculation,
    )

    private fun line(prompt: String, tag: String) = prompt.lines().first { it.startsWith("$tag:") }

    @Test
    fun context_sendsOneTargetWithItsLabelAndNoDayVariant() {
        val prompt = buildCoachContext(listOf(reading(1, 0, 69f, 35.5f, 24.5f), reading(2, 10, 68f, 34f, 23.1f))).prompt

        assertTrue(line(prompt, "TARGET_ABITUALE_KCAL"), line(prompt, "TARGET_ABITUALE_KCAL").startsWith("TARGET_ABITUALE_KCAL:tdee=2974,target=2528,p=179,c=292,f=72"))
        assertEquals(1, prompt.lines().count { it.startsWith("TARGET_") })
        assertTrue(!prompt.contains("TARGET_OPERATIVO_OGGI_KCAL"))
        assertTrue(!prompt.contains("tipo_giorno"))
        assertTrue(prompt.contains("REGOLA_TARGET:"))
        // The old unlabelled lines are gone: a bare `target=` would be ambiguous.
        assertTrue(!prompt.contains("CALORIE_E_MACRO"))
        assertTrue(!prompt.lines().any { it.startsWith("LC=") })
        assertTrue(line(prompt, "BMI_BMR").contains("bmrKcal=1789"))
    }

    @Test
    fun context_withoutACalculationTellsTheModelTheTargetIsUnavailable() {
        val prompt = BiaAnalysisReport.coachContext(
            profile = profile(), today = today, report = BiaAnalysisReport.build(listOf(reading(1, 0, 68f, 34f, 23.1f)))!!,
            bodyHistory = emptyList(), workoutHistory = emptyList(), planDays = emptyList(), calculation = null,
        ).prompt

        assertTrue(line(prompt, "TARGET_ABITUALE_KCAL").startsWith("TARGET_ABITUALE_KCAL:?"))
    }

    @Test
    fun target_isTheSameThatHomeAndFoodShow() {
        val metrics = OperationalNutritionMetrics.calculate(calculation)
        val prompt = buildCoachContext(listOf(reading(1, 0, 68f, 34f, 23.1f))).prompt

        assertTrue(line(prompt, "TARGET_ABITUALE_KCAL").contains("tdee=${Math.round(metrics.tdeeKcal!!)}"))
        assertTrue(line(prompt, "TARGET_ABITUALE_KCAL").contains("target=${Math.round(metrics.targetKcal!!)}"))
        assertTrue(BiaAnalysisReport.caloriesSection(calculation).contains("Target abituale: ${Math.round(metrics.targetKcal!!)} kcal"))
    }

    @Test
    fun context_carriesAppScenariosAndTheFatLossEstimateAsEstimates() {
        val result = buildCoachContext(listOf(reading(1, 0, 69f, 35.5f, 24.5f), reading(2, 10, 68f, 34f, 23.1f)))

        val scenarios = line(result.prompt, "SCENARI_APP")
        assertTrue(scenarios, scenarios.contains("S1 grasso_target=32%,peso_obiettivo=66.0kg,grasso_da_perdere=2.0kg"))
        assertEquals(3, result.scenarios.size)
        val fat = line(result.prompt, "GRASSO_PERSO_STIMA_APP")
        assertTrue(fat, fat.contains("vs_precedente STIMA_GRASSO_IN_CALO"))
        assertTrue(fat, fat.contains("grasso_perso_stimato=1.4kg"))
        assertTrue(fat, fat.contains("non fatti misurati"))
    }

    @Test
    fun context_marksChangesInsideNoiseAndMissingData() {
        val inside = buildCoachContext(listOf(reading(1, 0, 68.2f, 34.2f, 23.3f), reading(2, 10, 68f, 34f, 23.1f))).prompt
        assertTrue(line(inside, "GRASSO_PERSO_STIMA_APP").contains("ENTRO_RUMORE_NESSUNA_STIMA"))
        assertTrue(!line(inside, "GRASSO_PERSO_STIMA_APP").contains("grasso_perso_stimato"))

        val single = buildCoachContext(listOf(reading(1, 0, 68f, null, null))).prompt
        assertTrue(line(single, "SCENARI_APP").startsWith("SCENARI_APP:?"))
        assertTrue(line(single, "GRASSO_PERSO_STIMA_APP").startsWith("GRASSO_PERSO_STIMA_APP:?"))
    }

    @Test
    fun contextAndValidator_workTogetherOnTheOriginal2527Case() {
        val prompt = buildCoachContext(listOf(reading(1, 0, 69f, 35.5f, 24.5f), reading(2, 10, 68f, 34f, 23.1f))).prompt
        val validation = BiaAnalysisContract.ValidationContext(contextText = prompt)

        val labelled = interpretation(whatIsHappening = "Target abituale 2528 kcal, uguale per tutti i giorni.")
        assertTrue(BiaAnalysisContract.validateBusinessWith(labelled, validation).exceptionOrNull()?.message ?: "", BiaAnalysisContract.validateBusinessWith(labelled, validation).isSuccess)
        val invented = interpretation(calories = "Calorie target 2527 kcal.")
        assertEquals("NUMBER_NOT_IN_CONTEXT:2527 kcal", BiaAnalysisContract.validateBusinessWith(invented, validation).exceptionOrNull()?.message)
    }

    // ---- Presentation -----------------------------------------------------------------------------------

    @Test
    fun presentation_actionsTitleDependsOnTheClassificationAndCount() {
        val p = BiaAnalysisPresentation
        assertEquals(BiaAnalysisPresentation.ActionsTitle.THREE_PRIORITY, p.actionsTitle("STABLE", 3))
        assertEquals(BiaAnalysisPresentation.ActionsTitle.DO_NOW, p.actionsTitle("STABLE", 2))
        assertEquals(BiaAnalysisPresentation.ActionsTitle.DO_NOW, p.actionsTitle("MONITOR", 4))
        assertEquals(BiaAnalysisPresentation.ActionsTitle.DATA_TO_COLLECT, p.actionsTitle("INSUFFICIENT_DATA", 2))
        assertEquals(BiaAnalysisPresentation.ActionsTitle.DATA_TO_COLLECT, p.actionsTitle("INSUFFICIENT_DATA", 3))
        assertEquals(BiaAnalysisPresentation.ActionsTitle.DATA_TO_COLLECT, p.actionsTitle("INSUFFICIENT_DATA", 0))
    }

    @Test
    fun presentation_reliabilityIsAlwaysAWordIncludingOldItalianValues() {
        val p = BiaAnalysisPresentation
        assertEquals(BiaAnalysisPresentation.ReliabilityLabel.HIGH, p.reliabilityLabel("HIGH"))
        assertEquals(BiaAnalysisPresentation.ReliabilityLabel.MEDIUM, p.reliabilityLabel("MEDIUM"))
        assertEquals(BiaAnalysisPresentation.ReliabilityLabel.LOW, p.reliabilityLabel("LOW"))
        assertEquals(BiaAnalysisPresentation.ReliabilityLabel.HIGH, p.reliabilityLabel("alta"))
        assertEquals(BiaAnalysisPresentation.ReliabilityLabel.LOW, p.reliabilityLabel("Bassa"))
        assertEquals(BiaAnalysisPresentation.ReliabilityLabel.UNKNOWN, p.reliabilityLabel("?"))
        assertEquals(BiaAnalysisPresentation.ReliabilityLabel.UNKNOWN, p.reliabilityLabel(""))
    }
}
