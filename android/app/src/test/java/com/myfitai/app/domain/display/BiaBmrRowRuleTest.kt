package com.myfitai.app.domain.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiaBmrRowRuleTest {
    @Test
    fun hiddenWhenAbsent() {
        assertFalse(BiaBmrRowRule.visible(bmr = 1914, biaBmr = null))
    }

    @Test
    fun hiddenWhenItRepeatsTheLocalBmr() {
        assertFalse(BiaBmrRowRule.visible(bmr = 1914, biaBmr = 1914))
    }

    @Test
    fun shownWhenItDiffersFromTheLocalBmr() {
        assertTrue(BiaBmrRowRule.visible(bmr = 1914, biaBmr = 1929))
    }

    @Test
    fun shownWhenThereIsNoLocalBmrToCompareWith() {
        assertTrue(BiaBmrRowRule.visible(bmr = null, biaBmr = 1914))
    }
}
