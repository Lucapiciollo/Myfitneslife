package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiFailure
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.entity.UserProfileEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Flag L3: il BIA Coach spiega solo i flag calcolati dall'app; il validatore locale rifiuta tutto il resto. */
class BodyFlagCoachTest {
    private val waistFlags = setOf(BodyFlagCode.WHTR_HIGH, BodyFlagCode.WAIST_SECOND_LEVEL)

    private fun report(
        profile: BodyFlagProfile = FlagFixtures.profile(),
        waist: Float? = 108f,
    ): BodyFlagReport {
        val evaluation = FlagFixtures.eval(profile, body = listOfNotNull(waist?.let { FlagFixtures.body(3, waist = it) }))
        return BodyFlagReport(evaluation.flags, profile.heightCm, null, false, evaluation.thresholdsVersion, evaluation.skipped)
    }

    private val flagged get() = report()
    private val unflagged get() = report(waist = null)

    // ---- (1) contesto: blocco FLAG_APP -------------------------------------------------------------------------

    @Test
    fun contextLine_listsEveryFlagWithSeverityReliabilityEvidenceAndDates() {
        val line = BodyFlagCoach.contextLine(flagged)

        assertTrue(line, line.startsWith("FLAG_APP:"))
        assertEquals(2, flagged.flags.size)
        flagged.flags.forEach { flag ->
            assertTrue(line, line.contains("${flag.code.name}|gravita=${flag.severity.name}|affidabilita=${flag.reliability.name}|evidenza="))
        }
        assertTrue(line, line.contains("waistCm=108 cm (2026-11-11)"))
        assertTrue(line, line.contains("whtr=0.6 (2026-11-11)"))
        assertFalse(line, line.lines().size > 1)
        assertEquals(waistFlags, BodyFlagCoach.allowedCodes(flagged))
    }

    @Test
    fun contextLine_withoutFlags_statesTheInternalReasonInOneLine() {
        assertTrue(unflagged.flags.isEmpty())
        assertTrue(unflagged.skipped.isNotEmpty())

        val line = BodyFlagCoach.contextLine(unflagged)

        assertTrue(line, line.startsWith("FLAG_APP:nessun flag: regole non valutabili "))
        assertTrue(line, line.contains("WAIST_HEIGHT_RATIO:WAIST_MISSING"))
        assertTrue(BodyFlagCoach.allowedCodes(unflagged).isEmpty())
        // Nessuna regola saltata e nessun flag: soglie non superate. Servizio non disponibile: lo dice.
        assertTrue(BodyFlagCoach.contextLine(unflagged.copy(skipped = emptyList())).contains("nessun flag: nessuna soglia superata"))
        assertTrue(BodyFlagCoach.contextLine(null).startsWith("FLAG_APP:nessun flag: dati non disponibili"))
        assertTrue(BodyFlagCoach.allowedCodes(null).isEmpty())
    }

    private fun profileEntity() = UserProfileEntity(
        id = 1L, name = "Test", birthDateEpochDay = LocalDate.of(1986, 3, 10).toEpochDay(), heightCm = 180f, currentWeightKg = 90f,
        goal = "Ricomposizione", activityLevel = "Sedentario", wakeTimeMinutes = 420, sleepTimeMinutes = 1380,
        dietaryPreferencesJson = null, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L, biologicalSex = "Maschio",
    )

    private fun coachContext(flags: BodyFlagCoach.Input?) = BiaAnalysisReport.coachContext(
        profile = profileEntity(),
        today = FlagFixtures.TODAY,
        report = BiaAnalysisReport.build(listOf(FlagFixtures.bia(0, weight = 90f, fat = 20f)))!!,
        bodyHistory = emptyList(),
        planDays = emptyList(),
        calculation = null,
        zone = FlagFixtures.ZONE,
        flags = flags,
    )

    @Test
    fun coachPrompt_containsTheFlagBlockWithAndWithoutFlags_andOmitsItWhenNotRequested() {
        val withFlags = coachContext(BodyFlagCoach.Input(flagged))
        assertTrue(withFlags.prompt, withFlags.prompt.lines().any { it == BodyFlagCoach.contextLine(flagged) })
        assertEquals(waistFlags, withFlags.allowedFlagCodes)

        val without = coachContext(BodyFlagCoach.Input(unflagged))
        assertTrue(without.prompt, without.prompt.lines().any { it.startsWith("FLAG_APP:nessun flag:") })
        assertEquals(emptySet<BodyFlagCode>(), without.allowedFlagCodes)

        val unavailable = coachContext(BodyFlagCoach.Input(null))
        assertTrue(unavailable.prompt.contains("FLAG_APP:nessun flag: dati non disponibili"))

        // Chiamanti senza flag: prompt come prima e controlli spenti.
        val legacy = coachContext(null)
        assertFalse(legacy.prompt.contains("FLAG_APP"))
        assertNull(legacy.allowedFlagCodes)
    }

    // ---- (2) prompt e parita Gemini/OpenAI -----------------------------------------------------------------------

    @Test
    fun systemPrompt_carriesEveryFlagRule_andNoProfessionClaim() {
        val prompt = BiaAnalysisService.SYSTEM_PROMPT

        assertTrue(prompt.contains(BodyFlagCoach.PROMPT_SECTION))
        BodyFlagCoach.PROMPT_RULES.forEach { assertTrue(it, prompt.contains(it)) }
        assertTrue(prompt.contains("FLAG_APP"))
        assertTrue(prompt.contains("segnale da approfondire con un medico"))
        assertTrue(prompt.contains("test di forza"))
        assertTrue(prompt.contains("parlarne con un medico e NON dare"))
        assertFalse(prompt, prompt.contains("nutrizionista"))
        assertFalse(prompt, prompt.contains("dietista"))
        assertFalse(prompt, prompt.contains("specialista"))
    }

    private fun root(): File = listOf("../..", "..", ".").map(::File).first { File(it, "06-ai/providers/shared").isDirectory }

    private fun doc(path: String): String = File(root(), path).readText(Charsets.UTF_8)

    @Test
    fun flagRules_areIdenticalForBothProviders_andDocumentedVerbatim() {
        val shared = doc("06-ai/providers/shared/BODY_FLAGS_EXPLAIN_RULES.md")
        BodyFlagCoach.PROMPT_RULES.forEachIndexed { index, rule ->
            assertTrue("regola ${index + 1} assente nel documento condiviso: $rule", shared.contains("${index + 1}. $rule"))
        }
        val section = "## Flag corporei calcolati dall'app (FLAG_APP)"
        val sections = listOf(
            "06-ai/providers/gemini/agents/PROGRESS_ANALYSIS_AGENT_GEMINI.md",
            "06-ai/providers/openai/agents/PROGRESS_ANALYSIS_AGENT_GPT.md",
            "06-ai/agents/PROGRESS_ANALYSIS_AGENT.md",
        ).map { path -> doc(path).substringAfter(section, missingDelimiterValue = "").also { assertTrue("$path senza sezione flag", it.isNotBlank()) }.trim() }
        // Gemini e OpenAI hanno lo stesso identico testo (e coincide con l'agente canonico).
        assertEquals(1, sections.toSet().size)
        assertTrue(sections.first().contains("BODY_FLAGS_EXPLAIN_RULES.md"))
        // Il prompt e unico: non esistono regole flag specifiche di un provider.
        listOf(
            "06-ai/providers/gemini/agents/PROGRESS_ANALYSIS_AGENT_GEMINI.md",
            "06-ai/providers/openai/agents/PROGRESS_ANALYSIS_AGENT_GPT.md",
        ).forEach { path -> assertFalse(path, doc(path).contains("nutrizionista")) }
    }

    @Test
    fun professionClaims_areGoneFromAgentDocsAndFixedSentences() {
        listOf(
            "06-ai/agents/NUTRITION_AGENT.md",
            "06-ai/providers/gemini/agents/NUTRITION_AGENT_GEMINI.md",
            "06-ai/providers/shared/FOOD_CONSTRAINTS_LIMITS.md",
        ).forEach { path ->
            val text = doc(path).lowercase()
            assertFalse("$path: specialistic", text.contains("specialistic"))
            assertFalse("$path: dietista", text.contains("dietista"))
        }
        val note = BiaAnalysisAiJobHandler.DEFAULT_SAFETY_NOTE.lowercase()
        assertTrue(note.contains("assistente automatico"))
        assertFalse(note.contains("nutrizionista"))
        // Il testo salvato prima della modifica resta "nessuna nota" in visualizzazione.
        assertNull(BiaAnalysisPresentation.safetyNoteToShow(BiaAnalysisAiJobHandler.LEGACY_SAFETY_NOTE))
        assertNull(BiaAnalysisPresentation.safetyNoteToShow(BiaAnalysisAiJobHandler.DEFAULT_SAFETY_NOTE))
    }

    // ---- (3) validatore locale --------------------------------------------------------------------------------

    private fun interpretation(
        whatIsHappening: String = "Il trend resta nel rumore della misura.",
        monitor: List<String> = listOf("Il grasso corporeo va riconfermato con una nuova misura."),
        actions: List<String> = listOf("Ripeti la misura nelle stesse condizioni.", "Mantieni il piano attuale.", "Distribuisci le proteine nei pasti."),
        safety: String = "",
    ): BiaAnalysisContract.Interpretation = BiaAnalysisContract.parse(
        JSONObject().put(
            "data",
            (
                listOf(
                    "BA2", "B|MONITOR", "S|Andamento da monitorare nel periodo.", "C|$whatIsHappening", "F|?", "O|?", "K|?",
                    "P|Ripeti la misura tra tre settimane.", "L|MEDIUM|Storico coerente.",
                    "Y|La massa magra resta stabile tra le due misure.",
                ) + monitor.map { "M|$it" } + actions.map { "X|$it" } + listOfNotNull(safety.takeIf { it.isNotBlank() }?.let { "D|$it" }) + listOf("N|?", "V|1|ok")
                ).joinToString("\n"),
        ).toString(),
    )

    private val context get() = BodyFlagCoach.contextLine(flagged) + "\nBIA_CORRENTE:weightKg=89.5,bodyFatPercent=19.9"

    private fun reject(
        value: BiaAnalysisContract.Interpretation,
        allowed: Set<BodyFlagCode>? = waistFlags,
        safetyFlagActive: Boolean = false,
    ): String? = BiaAnalysisContract.validateBusinessWith(
        value,
        BiaAnalysisContract.ValidationContext(safetyFlagActive = safetyFlagActive, contextText = context, allowedFlagCodes = allowed),
    ).exceptionOrNull()?.message

    @Test
    fun validator_acceptsACorrectExplanationOfTheProvidedFlags() {
        val explained = interpretation(
            whatIsHappening = "La vita misura 108 cm, sopra la soglia indicata dall'app: e un segnale da approfondire con un medico.",
            monitor = listOf("Il rapporto vita altezza va rimisurato con calma."),
        )
        assertNull(reject(explained))
        // Nessun flag: una frase neutra e accettata.
        assertNull(reject(interpretation(whatIsHappening = "Nessun segnale corporeo da approfondire nei dati disponibili."), allowed = emptySet()))
        // Un flag sul muscolo con l'invito a un test di forza e a un medico.
        val muscle = interpretation(whatIsHappening = "Il muscolo scheletrico e un segnale da approfondire: un test di forza e un medico possono chiarirlo.")
        assertNull(reject(muscle, allowed = setOf(BodyFlagCode.SKELETAL_MUSCLE_DECLINE)))
    }

    @Test
    fun validator_rejectsASignalWithoutItsFlag() {
        val waist = interpretation(whatIsHappening = "La vita e un segnale da approfondire con un medico.")
        assertEquals("BODY_FLAG_SIGNAL_NOT_PROVIDED:WAIST", reject(waist, allowed = emptySet()))
        assertEquals("BODY_FLAG_SIGNAL_NOT_PROVIDED:WAIST", reject(waist, allowed = setOf(BodyFlagCode.VISCERAL_FAT_RISING)))

        val visceral = interpretation(monitor = listOf("Il grasso viscerale e un segnale da approfondire."))
        assertEquals("BODY_FLAG_SIGNAL_NOT_PROVIDED:VISCERAL", reject(visceral))

        val muscle = interpretation(whatIsHappening = "Il muscolo mostra un segnale di calo: parlane con un medico.")
        assertEquals("BODY_FLAG_SIGNAL_NOT_PROVIDED:MUSCLE", reject(muscle))
        assertNull(reject(muscle, allowed = setOf(BodyFlagCode.LEAN_MASS_LOSS_SHARE)))

        val weightLoss = interpretation(whatIsHappening = "Il calo di peso rapido e un segnale da approfondire.")
        assertEquals("BODY_FLAG_SIGNAL_NOT_PROVIDED:RAPID_WEIGHT_LOSS", reject(weightLoss))
        // Frasi ordinarie senza marcatore o "stile di vita" non sono segnalazioni.
        assertNull(reject(interpretation(whatIsHappening = "La circonferenza vita e diminuita rispetto alla misura precedente."), allowed = emptySet()))
        assertNull(reject(interpretation(whatIsHappening = "Il tuo stile di vita non cambia il segnale della misura."), allowed = emptySet()))
    }

    @Test
    fun validator_rejectsAFlagCodeThatWasNotProvided() {
        val invented = interpretation(whatIsHappening = "Il flag WHTR_HIGH e presente nei dati.")
        assertEquals("BODY_FLAG_CODE_NOT_PROVIDED:WHTR_HIGH", reject(invented, allowed = setOf(BodyFlagCode.WAIST_FIRST_LEVEL)))
        assertEquals("BODY_FLAG_CODE_NOT_PROVIDED:WHTR_HIGH", reject(interpretation(whatIsHappening = "Vale body_flag_whtr_high per questa misura."), allowed = emptySet()))
        // Un codice presente tra quelli passati non e un segnale inventato.
        assertNull(reject(invented, allowed = waistFlags))
    }

    @Test
    fun validator_rejectsDiagnosticAndPrescriptiveTerms_butToleratesANegatedDisclaimer() {
        listOf(
            "Potrebbe trattarsi di sarcopenia." to "sarcopenia",
            "Questa e una diagnosi di obesita." to "diagnosi",
            "Si tratta di una malattia metabolica." to "malattia",
            "Conviene iniziare una terapia." to "terapia",
        ).forEach { (text, term) ->
            assertEquals(text, "BODY_FLAG_FORBIDDEN_TERM:$term", reject(interpretation(whatIsHappening = text)))
        }
        assertEquals(
            "BODY_FLAG_FORBIDDEN_TERM:dosaggio",
            reject(interpretation(actions = listOf("Assumi un dosaggio giornaliero.", "Mantieni il piano attuale.", "Ripeti la misura."))),
        )
        // Anche nei campi di elenco e nella nota di sicurezza.
        assertEquals("BODY_FLAG_FORBIDDEN_TERM:diabete", reject(interpretation(monitor = listOf("Controlla se hai il diabete."))))
        assertNull(reject(interpretation(whatIsHappening = "Non e una diagnosi, ma solo una stima dell'app.")))
        assertNull(reject(interpretation(whatIsHappening = "L'app non da indicazioni terapeutiche e senza dosaggi.")))
        // I disturbi alimentari si possono nominare per rimandare al medico (regola di sicurezza esistente).
        assertNull(reject(interpretation(safety = "In caso di disturbi alimentari parlane con un medico."), safetyFlagActive = true))
    }

    @Test
    fun validator_rejectsMeasureNumbersNotInTheContext_andAcceptsTheOnesPassed() {
        val ok = interpretation(whatIsHappening = "La vita misura 108 cm e il peso e 89,5 kg.")
        assertNull(reject(ok))
        val foreign = interpretation(whatIsHappening = "La vita misura 130 cm nelle ultime misure.")
        val message = reject(foreign)
        assertTrue(message, message?.startsWith("NUMBER_NOT_IN_CONTEXT:130") == true)
    }

    @Test
    fun validator_exemptsTheSafetyNoteFromTheSignalRule_andIsOffWithoutFlagContext() {
        val bmiNote = interpretation(safety = "Il BMI e basso: ne parli con un medico.")
        assertNull(reject(bmiNote, allowed = emptySet(), safetyFlagActive = true))
        // Vecchi risultati e chiamanti senza flag: nessun nuovo controllo.
        val signal = interpretation(whatIsHappening = "La vita e un segnale da approfondire. Potrebbe trattarsi di sarcopenia.")
        assertNull(reject(signal, allowed = null))
    }

    // ---- (4) percorso esistente di fallimento ----------------------------------------------------------------

    private fun gateway(answer: String) = object : AiRuntimeGateway {
        override suspend fun execute(
            request: AiStructuredRequest,
            maxSchemaRetries: Int,
            businessValidator: (String) -> Result<Unit>,
        ): AiExecutionService.ValidatedResponse {
            val json = JSONObject().put("data", answer).toString()
            businessValidator(json).getOrThrow()
            return AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "fake", json)
        }
    }

    private fun answer(whatIsHappening: String) = listOf(
        "BA2", "B|MONITOR", "S|Andamento da monitorare nel periodo.", "C|$whatIsHappening", "F|?", "O|?", "K|?", "P|Ripeti la misura.",
        "L|MEDIUM|Storico coerente.", "Y|La massa magra resta stabile.", "M|Il grasso va riconfermato presto.",
        "X|Una azione", "X|Due azioni", "X|Tre azioni", "N|?", "V|1|ok",
    ).joinToString("\n")

    @Test
    fun service_rejectsAnInventedSignalWithAStableBusinessCode_andAcceptsTheExplanation() = runBlocking {
        val payload = context
        val report = BiaAnalysisService.Report(payload, 2, allowedFlagCodes = waistFlags)

        val failure = runCatching { BiaAnalysisService(gateway(answer("Il grasso viscerale e un segnale da approfondire."))).analyze(report) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("BODY_FLAG_SIGNAL_NOT_PROVIDED", AiFailure.stableBusinessCode(failure?.message))

        val result = BiaAnalysisService(gateway(answer("La vita misura 108 cm: e un segnale da approfondire con un medico."))).analyze(report)
        assertEquals("MONITOR", result.interpretation.classification)

        // Il codice e un rifiuto di business: stessa presentazione e stesso 'Riprova' degli altri rifiuti BIA.
        listOf("BODY_FLAG_SIGNAL_NOT_PROVIDED", "BODY_FLAG_CODE_NOT_PROVIDED", "BODY_FLAG_FORBIDDEN_TERM").forEach {
            assertTrue(it, it in AiFailure.BUSINESS_CODES)
            assertNotNull(it, com.myfitai.app.ai.AiContractRegistry.require(BiaAnalysisContract.SCHEMA_NAME).fixHint("$it:x"))
            assertTrue(it, com.myfitai.app.ai.AiFailureMessages.resolveForBiaAnalysis(it).canRetry)
        }
    }

    // ---- (5) vocabolario centralizzato -----------------------------------------------------------------------

    @Test
    fun vocabulary_isOneSharedListAndStemsMatchWholeWordsStartsOnly() {
        assertTrue(BodyFlagVocabulary.STEMS.containsAll(listOf("diagnos", "sarcopen", "malatt", "obesit", "patolog", "terapi", "prescri")))
        assertFalse("disturb" in BodyFlagVocabulary.COACH_OUTPUT_STEMS)
        assertTrue(BodyFlagVocabulary.NEGATABLE_STEMS.none { it in listOf("sarcopen", "malatt", "obesit", "patolog") })
        // Parole che contengono "cura" in mezzo non sono termini vietati.
        assertNull(BodyFlagCoach.firstForbiddenTerm(listOf("Misura accurata e sicurezza dei dati, nessun problema di scrittura.")))
        if (BodyFlagCoach.firstForbiddenTerm(listOf("Serve una cura immediata.")) == null) fail("cura non rilevata")
    }
}
