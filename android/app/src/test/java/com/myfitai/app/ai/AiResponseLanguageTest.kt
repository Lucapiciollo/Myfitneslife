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
        assertTrue(prompt.contains("meal titles"))
        assertTrue(prompt.contains("Preserve schema property names, pipe record letters"))
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
}
