package com.myfitai.app.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaAnalysisContractTest {
    @Test
    fun ba2_parsesRequiredSectionsAndThreeActions() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|PROBABLY_POSITIVE
            S|Andamento probabilmente positivo con grasso in calo.
            C|Il peso e la massa grassa sono misure recenti; la variazione muscolare resta da confermare.
            F|Circa 3 kg in uno scenario moderato, se la massa magra venisse mantenuta.
            O|Il target calorico è coerente con il dimagrimento, ma servono almeno tre settimane.
            K|Calorie circa 2100 e macro compatibili entro gli arrotondamenti.
            P|Ripeti tra 3 settimane al mattino nelle stesse condizioni.
            L|MEDIUM|Due rilevazioni comparabili ma poche informazioni su forza e recupero.
            R|Peso|90.0 kg|89.0 kg|-1.0 kg|Calo compatibile con il percorso.|MEDIUM
            H|30 giorni|Il trend mostra calo graduale del peso.
            G|Primo obiettivo|20%|86 kg|3 kg|Massa magra ipotizzata stabile.|MEDIUM
            Y|La circonferenza vita è diminuita.
            M|Verifica se la forza resta stabile.
            X|Mantieni il target attuale per altre due settimane.
            X|Distribuisci le proteine nei pasti principali.
            X|Ripeti la BIA nelle stesse condizioni.
            D|Analisi informativa: per dubbi clinici consulta un professionista.
            N|Qual è la tua media dei passi giornalieri?
            V|1|Dati interpretati senza valori inventati.
        """.trimIndent()))

        assertEquals("PROBABLY_POSITIVE", response.classification)
        assertEquals(1, response.comparisons.size)
        assertEquals(1, response.scenarios.size)
        assertEquals(3, response.actions.size)
        val validation = BiaAnalysisContract.validateBusiness(response)
        assertTrue(validation.exceptionOrNull()?.message ?: "validation failed", validation.isSuccess)
    }

    @Test
    fun ba2_rejectsMedicalOrCausalClaims() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|MONITOR
            S|Diagnosi di disidratazione.
            C|Dato da monitorare.
            F|?
            O|?
            K|?
            P|Ripeti la misura.
            L|LOW|Storico breve.
            X|Mantieni le condizioni di misura.
            X|Registra le calorie.
            X|Controlla il trend.
            D|Consulta un professionista se necessario.
            N|?
            V|1|?
        """.trimIndent()))

        assertTrue(BiaAnalysisContract.validateBusiness(response).isFailure)
    }

    @Test
    fun ba2_allowsInsufficientDataWithoutThreeActions() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|INSUFFICIENT_DATA
            S|Dati insufficienti per descrivere un andamento.
            C|È disponibile una sola rilevazione.
            F|Non calcolabile.
            O|Non valutabile.
            K|Non ricevute.
            P|Ripeti la BIA nelle stesse condizioni.
            L|LOW|Manca uno storico confrontabile.
            X|Ripeti la BIA nelle stesse condizioni.
            X|Registra il peso per almeno due settimane.
            X|Mantieni il percorso senza modifiche automatiche.
            D|Per dubbi clinici consulta un professionista.
            N|?
            V|1|Storico insufficiente.
        """.trimIndent()))

        val validation = BiaAnalysisContract.validateBusiness(response)
        assertTrue(validation.exceptionOrNull()?.message ?: "validation failed", validation.isSuccess)
    }

    @Test
    fun ba2_normalizesProviderResponseCollapsedIntoOneLine() {
        val response = BiaAnalysisContract.parse(envelope(
            "BA2B|STABLE S|Stabile C|Una sola rilevazione utile F|? O|? K|? P|Ripeti la misura L|LOW|Storico breve R|Peso|?|?|?|Dato|LOW X|Una X|Due X|Tre D|Analisi informativa, non diagnosi medica N|? V|1|ok"
        ))

        assertEquals("STABLE", response.classification)
        assertEquals(3, response.actions.size)
        val validation = BiaAnalysisContract.validateBusiness(response)
        assertTrue(validation.exceptionOrNull()?.message ?: "validation failed", validation.isSuccess)
    }

    @Test
    fun ba2_normalizesLiteralEscapedNewlinesInsideEnvelopeData() {
        val response = BiaAnalysisContract.parse(envelope(
            "BA2\\nB|STABLE\\nS|Stabile\\nC|Trend da monitorare\\nF|?\\nO|?\\nK|?\\nP|Ripeti la misura\\nL|LOW|Storico breve\\nX|Una\\nX|Due\\nX|Tre\\nD|Analisi informativa, non diagnosi medica\\nN|?\\nV|1|ok"
        ))

        assertEquals("STABLE", response.classification)
        assertEquals(3, response.actions.size)
    }

    @Test
    fun ba2_normalizesPrefixAttachedToFirstRecordInMultilineResponse() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|LOW|Storico breve
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|1|ok
        """.trimIndent()))

        assertEquals("STABLE", response.classification)
        assertEquals(3, response.actions.size)
    }

    @Test
    fun ba2_acceptsMissingHistoricalSummaryAndPipeAfterVersion() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2|B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|LOW|Storico breve
            H|30 giorni
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|1|ok
        """.trimIndent()))

        assertEquals("STABLE", response.classification)
        assertEquals("?", response.historical.single().summary)
    }

    @Test
    fun ba2_acceptsScenarioWithoutOptionalReliability() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|LOW|Storico breve
            G|Primo obiettivo|20%|86 kg|3 kg|Massa magra ipotizzata stabile.
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|1|ok
        """.trimIndent()))

        assertEquals("?", response.scenarios.single().reliability)
        assertTrue(BiaAnalysisContract.validateBusiness(response).isSuccess)
    }

    @Test
    fun ba2_preservesSafetyNoteWhenProviderAddsPipeFields() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|LOW|Storico breve
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa|non diagnosi medica|consulta un professionista se necessario
            N|?
            V|1|ok
        """.trimIndent()))

        assertTrue(response.safetyNote.contains("consulta un professionista"))
    }

    @Test
    fun ba2_preservesCaloriesTextWhenProviderAddsPipeFields() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|Calorie coerenti|macro da confermare|arrotondamenti contenuti
            P|Ripeti la misura
            L|LOW|Storico breve
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|1|ok
        """.trimIndent()))

        assertTrue(response.caloriesAndMacros.contains("macro da confermare"))
    }

    @Test
    fun ba2_acceptsNonNumericAgentValidationStatus() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|LOW|Storico breve
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|VALID|ok
        """.trimIndent()))

        assertEquals("ok", response.agentValidation)
    }

    @Test
    fun ba2_acceptsAgentValidationWithoutOptionalNotes() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|LOW|Storico breve
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|VALID
        """.trimIndent()))

        assertEquals("?", response.agentValidation)
    }

    @Test
    fun ba2_normalizesItalianReliabilityLevels() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|ALTA|Storico coerente
            R|Peso|90 kg|89 kg|-1 kg|Calo|BASSA
            G|Obiettivo|20%|86 kg|3 kg|Ipotesi esplicita|MEDIA
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|1|ok
        """.trimIndent()))

        assertEquals("HIGH", response.reliability)
        assertEquals("LOW", response.comparisons.single().reliability)
        assertEquals("MEDIUM", response.scenarios.single().reliability)
        assertTrue(BiaAnalysisContract.validateBusiness(response).isSuccess)
    }

    @Test
    fun ba2_ignoresUnknownExtensionRecordsAndKeepsRequiredAnalysis() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|STABLE
            S|Stabile
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|LOW|Storico breve
            X|Una
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|1|ok
            Q|Provider metadata
        """.trimIndent()))

        assertEquals("STABLE", response.classification)
        assertEquals(3, response.actions.size)
        assertTrue(BiaAnalysisContract.validateBusiness(response).isSuccess)
    }

    @Test
    fun ba2_preservesPipesInsideFreeTextFields() {
        val response = BiaAnalysisContract.parse(envelope("""
            BA2
            B|STABLE
            S|Stabile | da confermare
            C|Trend da monitorare
            F|?
            O|?
            K|?
            P|Ripeti la misura
            L|LOW|Storico breve
            X|Una | azione
            X|Due
            X|Tre
            D|Analisi informativa, non diagnosi medica
            N|?
            V|1|ok
        """.trimIndent()))

        assertEquals("Stabile | da confermare", response.verdict)
        assertEquals("Una | azione", response.actions.first())
    }

    private fun envelope(data: String): String = org.json.JSONObject().put("data", data).toString()
}
