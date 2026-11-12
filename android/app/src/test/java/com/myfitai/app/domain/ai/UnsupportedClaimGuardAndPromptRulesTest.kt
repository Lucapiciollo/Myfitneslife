package com.myfitai.app.domain.ai

import com.myfitai.app.domain.body.BodyProportionAnalysisService
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.progress.ProgressAnalysisService
import com.myfitai.app.domain.review.WeeklyReviewService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1: shared causal/overclaim guard terms and the safety rules that every body-related system prompt must carry. */
class UnsupportedClaimGuardAndPromptRulesTest {
    private val causal = UnsupportedClaimGuard.CAUSAL_ASSERTIONS
    private val overclaims = UnsupportedClaimGuard.BODY_OVERCLAIMS

    @Test
    fun causalAssertionsAreDetected() {
        listOf(
            "Il peso è salito, dovuto a uno sgarro",
            "La variazione è dovuta a meno sonno",
            "Aumento a causa di più sodio",
            "La settimana ha portato a un calo",
            "Il calo è il risultato di più passi",
            "Il calo e il risultato di più passi",
            "Il calo è stato causato dal deficit",
            "Weight change caused by sodium",
        ).forEach { assertTrue(it, UnsupportedClaimGuard.containsAffirmed(it, causal)) }
    }

    @Test
    fun negatedCausalAssertionsAreAccepted() {
        listOf(
            "Il calo non è dovuto a uno sgarro",
            "Non è a causa di più sodio",
            "Senza dire che ha portato a un calo",
            "Nessuna variazione è il risultato di una sola causa",
        ).forEach { assertFalse(it, UnsupportedClaimGuard.containsAffirmed(it, causal)) }
    }

    @Test
    fun overclaimsAreDetectedAndNegationIsRespected() {
        listOf(
            "Perdita di massa muscolare confermata",
            "Si osserva disidratazione",
            "La ritenzione = grasso",
            "La ritenzione idrica è grasso",
            "La ritenzione equivale a grasso",
        ).forEach { assertTrue(it, UnsupportedClaimGuard.containsAffirmed(it, overclaims)) }
        listOf(
            "Il dato non indica disidratazione",
            "Non c'è perdita di massa muscolare confermata",
            "La ritenzione idrica non è grasso",
        ).forEach { assertFalse(it, UnsupportedClaimGuard.containsAffirmed(it, overclaims)) }
    }

    @Test
    fun neutralTextIsNotFlagged() {
        val text = "Il peso è sceso di 0,5 kg e la vita è stabile"
        assertFalse(UnsupportedClaimGuard.containsAffirmed(text, causal + overclaims))
    }

    @Test
    fun bodyRelatedSystemPromptsCarryTheSafetyRules() {
        val rules = UnsupportedClaimGuard.PROMPT_SAFETY_RULES
        assertTrue(rules.contains("not fat gain"))
        assertTrue(rules.contains("Do not alarm"))
        assertTrue(rules.contains("do not judge the body"))
        assertTrue(rules.contains("Italian"))
        listOf(
            "weekly review" to WeeklyReviewService.SYSTEM_PROMPT,
            "progress analysis" to ProgressAnalysisService.SYSTEM_PROMPT,
            "body proportions" to BodyProportionAnalysisService.SYSTEM_PROMPT,
        ).forEach { (name, prompt) -> assertTrue("$name prompt must contain the shared rules", prompt.contains(rules)) }
    }

    @Test
    fun weeklyReviewPromptDeclaresTheIntervalAndTheEnforcedLimits() {
        val prompt = WeeklyReviewService.SYSTEM_PROMPT
        assertTrue(prompt.contains("DS="))
        assertTrue(prompt.contains("Summary <=25 words"))
        assertTrue(prompt.contains("O <=18") || prompt.contains("each O/G <=18 words"))
        assertTrue(prompt.contains("O 1..6"))
        assertTrue(prompt.contains("G 1..5"))
    }

    @Test
    fun explicitProfileAlwaysWinsOverTheActiveOne() {
        assertEquals(7L, PersonalResponseService.resolveProfileId(explicit = 7L, active = 3L))
        assertEquals(7L, PersonalResponseService.resolveProfileId(explicit = 7L, active = null))
        assertEquals(3L, PersonalResponseService.resolveProfileId(explicit = null, active = 3L))
        assertEquals(null, PersonalResponseService.resolveProfileId(explicit = null, active = null))
    }
}
