package com.myfitai.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseLanguageTest {
    @Test
    fun compactAgentsKeepProtocolWhileEnforcingItalianUserText() {
        val prompt = AiResponseLanguage.scope(
            globalScope = "SCOPE:NUTRITION_ONLY",
            compactRule = "COMPACT: MFP1 and pipe format only",
            agentPrompt = "Generate a weekly plan with M and I records.",
            compact = true,
        )
        assertTrue(prompt.contains("OUTPUT_LANGUAGE: it-IT"))
        assertTrue(prompt.contains("meal titles, recipes, preparation, ingredients"))
        assertTrue(prompt.contains("only Italian words and Italian grammar"))
        assertTrue(prompt.contains("Do not use English words"))
        assertTrue(prompt.contains("schema property names, pipe record letters"))
        assertTrue(prompt.contains("COMPACT: MFP1 and pipe format only"))
        assertTrue(prompt.contains("Generate a weekly plan with M and I records."))
        assertTrue(prompt.indexOf("OUTPUT_LANGUAGE") < prompt.indexOf("Generate a weekly plan"))
    }

    @Test
    fun regularStructuredAgentsAlsoReceiveItalianRuleWithoutCompactProtocol() {
        val prompt = AiResponseLanguage.scope(
            globalScope = "SCOPE:NUTRITION_ONLY",
            compactRule = "COMPACT",
            agentPrompt = "Give five food alternatives",
            compact = false,
        )
        assertTrue(prompt.contains("OUTPUT_LANGUAGE: it-IT"))
        assertTrue(prompt.contains("Give five food alternatives"))
        assertFalse(prompt.contains("\nCOMPACT\n"))
    }

    @Test
    fun languageRuleAllowsOnlyExplicitTechnicalExceptions() {
        val rule = AiResponseLanguage.ITALIAN_OUTPUT_RULE

        assertTrue(rule.contains("provider names, model names, brands and user quotations"))
        assertTrue(rule.contains("English is allowed only in structural tokens"))
        assertTrue(rule.contains("No extra text outside the requested JSON/pipe format"))
    }
}
