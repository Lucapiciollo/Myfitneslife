package com.myfitai.app.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLevelChoicesTest {
    private val base = listOf("Sedentario", "Moderato", "Molto attivo")

    @Test
    fun noStoredValueOrAValueAmongTheThreeChoicesLeavesTheOptionsUnchanged() {
        assertEquals(base, ActivityLevelChoices.options(base, null))
        assertEquals(base, ActivityLevelChoices.options(base, ""))
        assertEquals(base, ActivityLevelChoices.options(base, "   "))
        assertEquals(base, ActivityLevelChoices.options(base, "Moderato"))
        assertEquals(base, ActivityLevelChoices.options(base, " molto attivo "))
    }

    @Test
    fun aHistoricalValueOutsideTheThreeChoicesIsKeptAsIsAfterThem() {
        assertEquals(base + "Leggermente attivo", ActivityLevelChoices.options(base, "Leggermente attivo"))
        assertEquals(base + "Estremamente attivo", ActivityLevelChoices.options(base, "Estremamente attivo"))
        assertEquals(base + "Moderatamente attivo", ActivityLevelChoices.options(base, "Moderatamente attivo"))
    }

    @Test
    fun theStoredValueIsNeverRewrittenOrTrimmedIntoAnotherChoice() {
        assertEquals(base + "Moderatamente attivo", ActivityLevelChoices.options(base, "  Moderatamente attivo "))
    }

    @Test
    fun isLegacyDistinguishesTheNewChoicesFromHistoricalValues() {
        assertFalse(ActivityLevelChoices.isLegacy(base, null))
        assertFalse(ActivityLevelChoices.isLegacy(base, "sedentario"))
        assertTrue(ActivityLevelChoices.isLegacy(base, "Leggermente attivo"))
        assertTrue(ActivityLevelChoices.isLegacy(base, "Moderatamente attivo"))
    }
}

