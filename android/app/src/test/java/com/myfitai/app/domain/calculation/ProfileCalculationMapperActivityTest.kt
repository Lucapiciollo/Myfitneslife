package com.myfitai.app.domain.calculation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCalculationMapperActivityTest {
    private val sedentary = LocalCalculationEngine.ActivityLevel.SEDENTARY
    private val moderate = LocalCalculationEngine.ActivityLevel.MODERATE
    private val veryActive = LocalCalculationEngine.ActivityLevel.VERY_ACTIVE

    @Test
    fun theThreeCurrentLabelsMapToTheirFactors() {
        assertEquals(sedentary, ProfileCalculationMapper.activity("Sedentario"))
        assertEquals(moderate, ProfileCalculationMapper.activity("Moderato"))
        assertEquals(veryActive, ProfileCalculationMapper.activity("Molto attivo"))
        assertEquals(1.20, sedentary.multiplier, 0.0)
        assertEquals(1.55, moderate.multiplier, 0.0)
        assertEquals(1.725, veryActive.multiplier, 0.0)
    }

    @Test
    fun everyHistoricalValueStaysReadable() {
        assertEquals(LocalCalculationEngine.ActivityLevel.LIGHT, ProfileCalculationMapper.activity("Leggermente attivo"))
        assertEquals(1.375, LocalCalculationEngine.ActivityLevel.LIGHT.multiplier, 0.0)
        assertEquals(moderate, ProfileCalculationMapper.activity("Moderatamente attivo"))
        assertEquals(LocalCalculationEngine.ActivityLevel.EXTREME, ProfileCalculationMapper.activity("Estremamente attivo"))
        assertEquals(1.90, LocalCalculationEngine.ActivityLevel.EXTREME.multiplier, 0.0)
    }

    @Test
    fun caseAndSurroundingSpacesAreTolerated() {
        assertEquals(moderate, ProfileCalculationMapper.activity("  MODERATO "))
        assertEquals(sedentary, ProfileCalculationMapper.activity("sEdEnTaRiO"))
        assertEquals(veryActive, ProfileCalculationMapper.activity(" molto ATTIVO"))
    }

    @Test
    fun missingOrUnknownValuesGiveNull() {
        assertNull(ProfileCalculationMapper.activity(null))
        assertNull(ProfileCalculationMapper.activity(""))
        assertNull(ProfileCalculationMapper.activity("   "))
        assertNull(ProfileCalculationMapper.activity("Atleta"))
    }
}

