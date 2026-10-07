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
    fun recovery_capIsASharePerDayOfThatDaysOwnBaseTarget() {
        val monday = LocalDate.of(2026, 9, 14)
        val trainingBase = NutritionBusinessValidator.Targets(2600.0, 180.0, 330.0, 72.0)
        val restBase = NutritionBusinessValidator.Targets(2200.0, 180.0, 230.0, 72.0)
        val bases = (0L until 7L).associate { offset ->
            monday.plusDays(offset) to if (offset % 2L == 0L) trainingBase else restBase
        }

        val result = CalorieRecoveryEngine.plan(
            monday, monday, base, 90.0, LocalCalculationEngine.Goal.WEIGHT_LOSS,
            listOf(CalorieRecoveryEngine.Credit(1, monday.minusDays(1), 1500)),
            dailyBase = bases,
        )

        result.days.forEach { day ->
            val dayBase = bases.getValue(day.date)
            assertTrue("${day.date} recovered=${day.recoveredKcal}", day.recoveredKcal <= (dayBase.kcal * 0.10).toInt())
            assertEquals(dayBase.kcal - day.recoveredKcal, day.targets.kcal, 1.0)
            assertEquals(180.0, day.targets.proteinG, 1e-9)
        }
        assertTrue("a day with a larger base absorbs more", result.days.first().recoveredKcal >= result.days[1].recoveredKcal)
        assertEquals(1500, result.plannedRecoveryKcal)
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