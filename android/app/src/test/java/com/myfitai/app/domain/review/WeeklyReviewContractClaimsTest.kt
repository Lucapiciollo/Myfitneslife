package com.myfitai.app.domain.review

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReviewContractClaimsTest {
    @Test
    fun negatedDiagnosisInSummaryIsAccepted() {
        listOf(
            "Settimana descritta con dati registrati: non è una diagnosi medica.",
            "Osservazione informativa, non costituisce diagnosi.",
            "Riepilogo senza diagnosi né cause certe.",
            "Nessuna diagnosi è formulata.",
        ).forEach { summary ->
            val result = WeeklyReviewContract.validateBusiness(WeeklyReviewContract.parse(json(summary = summary)), WEEK)
            assertTrue("$summary: ${result.exceptionOrNull()?.message}", result.isSuccess)
        }
    }

    @Test
    fun negatedDiagnosisInObservationAndGuidanceIsAccepted() {
        val response = WeeklyReviewContract.parse(
            json(observation = "Il peso è un'osservazione, non una diagnosi.", guidance = "Continua senza diagnosi autonome."),
        )
        assertTrue(WeeklyReviewContract.validateBusiness(response, WEEK).isSuccess)
    }

    @Test
    fun assertedDiagnosisIsRejectedEvenWhenAnotherFieldDisclaims() {
        listOf(
            json(summary = "Diagnosi di disidratazione.", observation = "Non è una diagnosi medica."),
            json(observation = "Quadro compatibile con una diagnosi di ritenzione."),
            json(guidance = "Il medico conferma la diagnosis di carenza."),
            json(summary = "Non è una diagnosi, ma è una diagnosi di disidratazione."),
        ).forEach { raw ->
            val result = WeeklyReviewContract.validateBusiness(WeeklyReviewContract.parse(raw), WEEK)
            assertEquals("MEDICAL_CLAIM_NOT_ALLOWED", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun causalClaimsRemainRejected() {
        val result = WeeklyReviewContract.validateBusiness(
            WeeklyReviewContract.parse(json(summary = "Lo sgarro ha causato il cambiamento del peso.")),
            WEEK,
        )
        assertEquals("CAUSAL_CLAIM_NOT_ALLOWED", result.exceptionOrNull()?.message)
    }

    private fun json(
        summary: String = "Settimana descritta usando solo dati registrati.",
        observation: String = "Sono stati registrati tre allenamenti.",
        guidance: String = "Mantieni una pianificazione pratica.",
    ): String = JSONObject()
        .put("weekStartEpochDay", WEEK)
        .put("summary", summary)
        .put("observations", JSONArray().put(observation))
        .put("nextWeekGuidance", JSONArray().put(guidance))
        .put("agentValidation", JSONObject().put("valid", true).put("notes", "ok"))
        .toString()

    private companion object {
        const val WEEK = 100L
    }
}
