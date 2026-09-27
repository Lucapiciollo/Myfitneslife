package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NutritionPlanPeriodTest {
    private val monday = LocalDate.of(2026, 9, 14)

    @Test
    fun periodDates_areAnchoredToMonday() {
        assertEquals(monday, NutritionPlanPeriod.monday(monday.plusDays(3)))
        assertEquals(monday.plusDays(13), NutritionPlanPeriod.endDate(monday, 2))
        assertEquals(28, NutritionPlanPeriod.dates(monday, 4).size)
    }

    @Test
    fun currentWeek_excludesOnlyPastDays() {
        val today = monday.plusDays(3)
        val dates = NutritionPlanPeriod.dates(monday, 2, NutritionPlanPeriod.generationStart(monday, today))
        assertEquals(today, dates.first())
        assertEquals(monday.plusDays(13), dates.last())
        assertEquals(11, dates.size)
    }

    @Test
    fun completedPeriod_isRejected() {
        assertTrue(NutritionPlanPeriod.isEntirelyPast(monday, 1, monday.plusDays(7)))
        assertFalse(NutritionPlanPeriod.isEntirelyPast(monday, 2, monday.plusDays(7)))
    }
}
