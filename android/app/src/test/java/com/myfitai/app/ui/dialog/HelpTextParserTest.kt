package com.myfitai.app.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpTextParserTest {
    @Test
    fun headingBeforeColonIsSplitFromTheText() {
        val result = parseHelpParagraphs("BMR a riposo: energia stimata senza attivita.")
        assertEquals(listOf(HelpParagraph("BMR a riposo", null, "energia stimata senza attivita.")), result)
    }

    @Test
    fun headingValueAndExplanationAreParsedAsThreeRoles() {
        val result = parseHelpParagraphs("BMR locale: 1918 kcal\n  Stima usata per il target.").single()
        assertEquals("BMR locale", result.heading)
        assertEquals("1918 kcal", result.value)
        assertEquals("Stima usata per il target.", result.text)
    }

    @Test
    fun paragraphsAreSeparatedByBlankLinesInOrder() {
        val result = parseHelpParagraphs("Uno: a\n\nDue senza titolo\n   \nTre: c")
        assertEquals(listOf("Uno", null, "Tre"), result.map { it.heading })
        assertEquals(listOf(null, null, null), result.map { it.value })
        assertEquals(listOf("a", "Due senza titolo", "c"), result.map { it.text })
    }

    @Test
    fun leadingBulletIsRemoved() {
        val result = parseHelpParagraphs("\u2022 Target: valore del piano")
        assertEquals(HelpParagraph("Target", null, "valore del piano"), result.single())
    }

    @Test
    fun highlightCanBeDisabledForProseWithColons() {
        val prose = "Compare quando mancano valori: una singola misura descrive solo lo stato attuale."
        val result = parseHelpParagraphs(prose, highlightHeadings = false).single()
        assertNull(result.heading)
        assertNull(result.value)
        assertEquals(prose, result.text)
    }

    @Test
    fun colonAtTheStartIsNotAHeading() {
        assertNull(parseHelpParagraphs(": solo testo").single().heading)
    }

    @Test
    fun blankMessageProducesNoParagraphs() {
        assertTrue(parseHelpParagraphs("  \n\n  ").isEmpty())
    }
}
