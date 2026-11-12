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

    @Test
    fun newCausalPhrasesAreRejectedUnlessNegated() {
        listOf(
            "Il calo è dovuto a poche calorie.",
            "Peso più alto a causa di uno sgarro.",
            "La settimana ha portato a un aumento.",
            "Il valore e il risultato di meno allenamenti.",
        ).forEach { text ->
            val result = WeeklyReviewContract.validateBusiness(WeeklyReviewContract.parse(json(summary = text)), WEEK)
            assertEquals(text, "CAUSAL_CLAIM_NOT_ALLOWED", result.exceptionOrNull()?.message)
        }
        listOf("Il calo non è dovuto a poche calorie.", "Il calo non è a causa di uno sgarro.").forEach { text ->
            val result = WeeklyReviewContract.validateBusiness(WeeklyReviewContract.parse(json(observation = text)), WEEK)
            assertTrue("$text: ${result.exceptionOrNull()?.message}", result.isSuccess)
        }
    }

    @Test
    fun bodyOverclaimsAreRejectedUnlessNegated() {
        listOf(
            "Perdita di massa muscolare confermata.",
            "Segnali di disidratazione evidenti.",
            "La ritenzione idrica è grasso accumulato.",
        ).forEach { text ->
            val result = WeeklyReviewContract.validateBusiness(WeeklyReviewContract.parse(json(guidance = text)), WEEK)
            assertEquals(text, "BODY_OVERCLAIM_NOT_ALLOWED", result.exceptionOrNull()?.message)
        }
        val negated = WeeklyReviewContract.validateBusiness(
            WeeklyReviewContract.parse(json(observation = "Il dato non indica disidratazione.")),
            WEEK,
        )
        assertTrue(negated.exceptionOrNull()?.message, negated.isSuccess)
    }

    @Test
    fun promptLimitsAreEnforced() {
        fun fail(raw: String, code: String) =
            assertEquals(code, WeeklyReviewContract.validateBusiness(WeeklyReviewContract.parse(raw), WEEK).exceptionOrNull()?.message)
        val tooLong = List(WeeklyReviewContract.MAX_SUMMARY_WORDS + 1) { "parola" }.joinToString(" ")
        fail(json(summary = tooLong), "SUMMARY_TOO_LONG")
        fail(json(observation = List(WeeklyReviewContract.MAX_ITEM_WORDS + 1) { "parola" }.joinToString(" ")), "ITEM_TOO_LONG")
        fail(json(guidance = List(WeeklyReviewContract.MAX_ITEM_WORDS + 1) { "parola" }.joinToString(" ")), "ITEM_TOO_LONG")
        fail(jsonWithCounts(observations = WeeklyReviewContract.MAX_OBSERVATIONS + 1, guidance = 1), "OBSERVATIONS_COUNT_INVALID")
        fail(jsonWithCounts(observations = 1, guidance = WeeklyReviewContract.MAX_GUIDANCE + 1), "GUIDANCE_COUNT_INVALID")

        val atLimit = List(WeeklyReviewContract.MAX_SUMMARY_WORDS) { "parola" }.joinToString(" ")
        val ok = WeeklyReviewContract.validateBusiness(
            WeeklyReviewContract.parse(jsonWithCounts(WeeklyReviewContract.MAX_OBSERVATIONS, WeeklyReviewContract.MAX_GUIDANCE, atLimit)),
            WEEK,
        )
        assertTrue(ok.exceptionOrNull()?.message, ok.isSuccess)
    }

    private fun jsonWithCounts(observations: Int, guidance: Int, summary: String = "Settimana descritta con dati registrati."): String = JSONObject()
        .put("weekStartEpochDay", WEEK)
        .put("summary", summary)
        .put("observations", JSONArray(List(observations) { "Osservazione $it." }))
        .put("nextWeekGuidance", JSONArray(List(guidance) { "Indicazione $it." }))
        .put("agentValidation", JSONObject().put("valid", true).put("notes", "ok"))
        .toString()

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
