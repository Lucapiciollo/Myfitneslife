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

    @Test
    fun recovery_periodCoversAllConfiguredDays() {
        val monday = LocalDate.of(2026, 9, 14)
        val result = CalorieRecoveryEngine.plan(
            monday, monday, base, 90.0, LocalCalculationEngine.Goal.MAINTENANCE, emptyList(), periodWeeks = 4,
        )
        assertEquals(28, result.days.size)
        assertEquals(monday.plusDays(27), result.days.last().date)
    }

    @Test
    fun recovery_capIsTenPercentOfTheSingleBaseTargetOnEveryDay() {
        val monday = LocalDate.of(2026, 9, 14)

        val result = CalorieRecoveryEngine.plan(
            monday, monday, base, 90.0, LocalCalculationEngine.Goal.WEIGHT_LOSS,
            listOf(CalorieRecoveryEngine.Credit(1, monday.minusDays(1), 1000)),
        )

        result.days.forEach { day ->
            assertTrue("${day.date} recovered=${day.recoveredKcal}", day.recoveredKcal <= (base.kcal * 0.10).toInt())
            assertEquals(base.kcal - day.recoveredKcal, day.targets.kcal, 1.0)
            assertEquals(base.proteinG, day.targets.proteinG, 1e-9)
        }
        assertEquals(1000, result.plannedRecoveryKcal)
    }

    @Test
    fun recovery_keepsTheHabitualProteinAndFatOfTheBaseTargetNotAPerKilogramRecalculation() {
        val monday = LocalDate.of(2026, 9, 14)
        // K6: base macros of the real case (78 kg, 1381 kcal): protein 103,6 g (30% kcal), fat 46,8 g. A per-kg recalculation
        // (2,0 g/kg) would put 156 g of protein back on every recovery day.
        val realBase = NutritionBusinessValidator.Targets(1381.0, 103.575, 136.375, 46.8)

        val result = CalorieRecoveryEngine.plan(
            monday, monday, realBase, 78.0, LocalCalculationEngine.Goal.WEIGHT_LOSS,
            listOf(CalorieRecoveryEngine.Credit(1, monday.minusDays(1), 500)),
            minimumKcal = 1200.0,
        )

        assertTrue(result.plannedRecoveryKcal > 0)
        result.days.forEach { day ->
            assertEquals(103.575, day.targets.proteinG, 0.0)
            assertEquals(46.8, day.targets.fatG, 0.0)
            assertEquals(
                (day.targets.kcal - 103.575 * 4 - 46.8 * 9) / 4.0,
                day.targets.carbsG,
                1e-9,
            )
        }
        assertTrue(result.days.any { it.recoveredKcal > 0 && it.targets.carbsG < realBase.carbsG })
    }

    @Test
    fun recovery_neverPushesADayBelowTheSafetyFloor() {
        val monday = LocalDate.of(2026, 9, 14)
        val lowBase = NutritionBusinessValidator.Targets(1600.0, 180.0, 100.0, 70.0)

        val result = CalorieRecoveryEngine.plan(
            monday, monday, lowBase, 90.0, LocalCalculationEngine.Goal.WEIGHT_LOSS,
            listOf(CalorieRecoveryEngine.Credit(1, monday.minusDays(1), 1000)),
            minimumKcal = 1500.0,
        )

        assertTrue(result.days.all { it.targets.kcal >= 1500.0 })
        assertTrue(result.days.all { it.recoveredKcal <= 100 })
        // Only what fits above the floor is allocated; the rest stays available for later weeks.
        assertEquals(700, result.plannedRecoveryKcal)
        assertEquals(300, result.remainingKcal)
    }

    @Test
    fun recovery_floorAboveTheBaseLeavesTheTargetUntouched() {
        val monday = LocalDate.of(2026, 9, 14)

        val result = CalorieRecoveryEngine.plan(
            monday, monday, base, 90.0, LocalCalculationEngine.Goal.WEIGHT_LOSS,
            listOf(CalorieRecoveryEngine.Credit(1, monday.minusDays(1), 500)),
            minimumKcal = 2500.0,
        )

        assertEquals(0, result.plannedRecoveryKcal)
        assertTrue(result.days.all { it.targets.kcal == base.kcal })
    }
}