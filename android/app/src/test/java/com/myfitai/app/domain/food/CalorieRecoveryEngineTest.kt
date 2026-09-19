package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalorieRecoveryEngineTest {
    private val base = NutritionBusinessValidator.Targets(2000.0, 180.0, 220.0, 70.0)

    @Test
    fun recovery_neverExceedsTenPercentPerDay_andPreservesProtein() {
        val monday = LocalDate.of(2026, 9, 14)
        val result = CalorieRecoveryEngine.plan(
            monday, monday, base, 90.0, LocalCalculationEngine.Goal.RECOMPOSITION,
            listOf(CalorieRecoveryEngine.Credit(1, monday.minusDays(1), 1000)),
        )
        assertTrue(result.days.all { it.recoveredKcal <= 200 })
        assertTrue(result.days.all { it.targets.kcal >= 1800.0 })
        assertTrue(result.days.all { it.targets.proteinG == 180.0 })
        assertEquals(1000, result.plannedRecoveryKcal)
        assertEquals(0, result.remainingKcal)
    }

    @Test
    fun recovery_usesOnlyEligibleFutureDays() {
        val monday = LocalDate.of(2026, 9, 14)
        val today = monday.plusDays(3)
        val source = monday.minusDays(3)
        val result = CalorieRecoveryEngine.plan(
            monday, today, base, 90.0, LocalCalculationEngine.Goal.WEIGHT_LOSS,
            listOf(CalorieRecoveryEngine.Credit(2, source, 1000)),
        )
        assertTrue(result.days.filter { it.date.isBefore(today) }.all { it.recoveredKcal == 0 })
        assertTrue(result.days.filter { it.date.isAfter(source.plusDays(7)) }.all { it.recoveredKcal == 0 })
    }

    @Test
    fun noCredit_meansNoTargetChange() {
        val monday = LocalDate.of(2026, 9, 14)
        val result = CalorieRecoveryEngine.plan(
            monday, monday, base, 90.0, LocalCalculationEngine.Goal.MAINTENANCE, emptyList(),
        )
        assertEquals(0, result.plannedRecoveryKcal)
        assertTrue(result.days.all { it.targets.kcal == base.kcal })
    }
}
