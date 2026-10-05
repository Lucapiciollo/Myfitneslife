package com.myfitai.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheatEntryNotesTest {
    @Test
    fun detailedMode_includesTrimmedNotes() {
        assertEquals("con amici", composeCheatNotes(detailed = true, notes = "  con amici ", clarification = ""))
    }

    @Test
    fun quickMode_ignoresHiddenNotes() {
        assertNull(composeCheatNotes(detailed = false, notes = "con amici", clarification = ""))
    }

    @Test
    fun clarificationIsKeptInBothModes() {
        val expected = "Chiarimento utente dopo la prima lettura IA: porzione grande"
        assertEquals(expected, composeCheatNotes(detailed = false, notes = "nascosta", clarification = "porzione grande"))
        assertEquals("fuori casa\n$expected", composeCheatNotes(detailed = true, notes = "fuori casa", clarification = "porzione grande"))
    }

    @Test
    fun blankInputsProduceNoNotes() {
        assertNull(composeCheatNotes(detailed = true, notes = "   ", clarification = " "))
    }

    @Test
    fun quantity_isSentOnlyInDetailedMode() {
        assertEquals("Grande", composeCheatQuantity(detailed = true, quantity = " Grande "))
        assertNull(composeCheatQuantity(detailed = false, quantity = "Grande"))
        assertNull(composeCheatQuantity(detailed = true, quantity = "  "))
        assertNull(composeCheatQuantity(detailed = true, quantity = null))
    }
}
