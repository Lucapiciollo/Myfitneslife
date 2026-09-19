package com.myfitai.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NutritionEstimateFormatterTest {

    @Test
    fun formatsKcalAsItalianEstimatedValue() {
        assertEquals("≈ 2.542 kcal", NutritionEstimateFormatter.formatEstimatedKcal(2542.4))
    }

    @Test
    fun formatsMacrosWithOneItalianDecimal() {
        assertEquals("≈ 181,4 g", NutritionEstimateFormatter.formatEstimatedMacro(181.44, "g"))
    }

    @Test
    fun formatsMissingValueAsDash() {
        assertEquals("—", NutritionEstimateFormatter.formatEstimatedKcal(null))
    }
}