package com.myfitai.app.navigation

import com.myfitai.app.ui.AiSettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsEntryTest {
    @Test
    fun everyEntryPointOpensTheDedicatedAiSettingsActivity() {
        assertEquals(AiSettingsActivity::class.java, AiSettingsEntry.destination)
    }

    @Test
    fun legacyAiTabValueIsRecognisedOnlyForTheOldTabName() {
        assertTrue(AiSettingsEntry.isLegacyAiTabRequest("AI"))
        assertTrue(AiSettingsEntry.isLegacyAiTabRequest(AiSettingsEntry.LEGACY_AI_TAB_VALUE))
        assertFalse(AiSettingsEntry.isLegacyAiTabRequest(null))
        assertFalse(AiSettingsEntry.isLegacyAiTabRequest(""))
        assertFalse(AiSettingsEntry.isLegacyAiTabRequest("ai"))
        BottomNavBinder.Tab.entries.forEach { tab ->
            assertFalse(tab.name, AiSettingsEntry.isLegacyAiTabRequest(tab.name))
        }
    }

    @Test
    fun bottomNavigationHasExactlyFourTabsAndNoAiTab() {
        assertEquals(
            listOf("HOME", "FOOD", "PROGRESS", "MORE"),
            BottomNavBinder.Tab.entries.map { it.name },
        )
        // Il valore storico "AI" non corrisponde più a nessun tab: l'host ricade su Home.
        assertTrue(BottomNavBinder.Tab.entries.none { it.name == AiSettingsEntry.LEGACY_AI_TAB_VALUE })
    }
}
