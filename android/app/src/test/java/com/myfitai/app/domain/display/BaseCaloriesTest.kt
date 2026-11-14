package com.myfitai.app.domain.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseCaloriesTest {
    @Test
    fun withoutSport_isBmrTimesSedentaryFactor() {
        assertEquals(2297, BaseCalories.withoutSport(1914.0))
        assertEquals(1625, BaseCalories.withoutSport(1354.0))
    }

    @Test
    fun withoutSport_isNullWithoutAValidBmr() {
        assertNull(BaseCalories.withoutSport(null))
        assertNull(BaseCalories.withoutSport(0.0))
        assertNull(BaseCalories.withoutSport(Double.NaN))
    }
}
