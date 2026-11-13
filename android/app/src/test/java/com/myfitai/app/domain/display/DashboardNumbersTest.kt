package com.myfitai.app.domain.display

import com.myfitai.app.data.repository.MealPlanWeekSelector
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.OperationalNutritionMetrics
import com.myfitai.app.domain.calculation.WeeklyBodyExpectation
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.ui.NutritionEstimateFormatter
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardNumbersTest {
    private val minus = "\u2212"

    // ---- (7) one formatter, one rounding rule -------------------------------------------------------------

    @Test
    fun goldenValuesRoundHalfUpAndGroupLikeEveryScreen() {
        // Profile D (62y, 164cm, 78kg) and U (40y, 180cm, 89.5kg) from the calculation audit.
        assertEquals("2.068 kcal", DisplayNumberFormatter.kcal(2067.7))
        assertEquals("1.758 kcal", DisplayNumberFormatter.kcal(1757.5))
        assertEquals("1.334 kcal", DisplayNumberFormatter.kcal(1334.0))
        assertEquals("2.829 kcal", DisplayNumberFormatter.kcal(2828.8))
        assertEquals("2.404 kcal", DisplayNumberFormatter.kcal(2404.4))
        assertEquals("143", DisplayNumberFormatter.decimal(143.0))
        assertEquals("261", DisplayNumberFormatter.decimal(261.0))
    }

    @Test
    fun foodEstimateFormatterUsesTheSameRuleAsHomeAndPdf() {
        assertEquals("\u2248 2.068 kcal", NutritionEstimateFormatter.formatEstimatedKcal(2067.7))
        assertEquals("\u2248 1.758 kcal", NutritionEstimateFormatter.formatEstimatedKcal(1757.5))
        // A tie must go up everywhere (the old DecimalFormat default was half-even: 2068.5 -> 2.068).
        assertEquals("\u2248 2.069 kcal", NutritionEstimateFormatter.formatEstimatedKcal(2068.5))
        assertEquals("2.069 kcal", DisplayNumberFormatter.kcal(2068.5))
        assertEquals("\u2248 181,4 g", NutritionEstimateFormatter.formatEstimatedMacro(181.44f, "g"))
    }

    @Test
    fun noPlusOrMinusZero() {
        assertEquals("0", DisplayNumberFormatter.signedDecimal(0.0f))
        assertEquals("0", DisplayNumberFormatter.signedDecimal(0.04f))
        assertEquals("0", DisplayNumberFormatter.signedDecimal(-0.04f))
        assertEquals("+0,1", DisplayNumberFormatter.signedDecimal(0.05f))
        assertEquals("${minus}0,1", DisplayNumberFormatter.signedDecimal(-0.05f))
        assertEquals("5", DisplayNumberFormatter.decimal(5.04))
        assertEquals("0", DisplayNumberFormatter.integer(-0.4))
        assertEquals("-", DisplayNumberFormatter.integer(Double.NaN))
    }

    // ---- (2) deltas: sign, direction icon, judgement only as reinforcement ---------------------------------

    @Test
    fun deltaGoingUpShowsPlusAndUpDirectionEvenWhenJudgedBad() {
        val fat = MetricDeltaPresenter.present(0.5f, "%", MetricDeltaPresenter.Preference.DOWN_IS_POSITIVE)
        assertEquals(MetricDeltaPresenter.Direction.UP, fat.direction)
        assertEquals(MetricDeltaPresenter.Judgement.NEGATIVE, fat.judgement)
        assertEquals("+0,5 %", fat.signedText)
        assertEquals("0,5 %", fat.magnitudeText)
    }

    @Test
    fun deltaGoingDownShowsMinusAndDownDirectionEvenWhenJudgedGood() {
        val fat = MetricDeltaPresenter.present(-1.2f, "%", MetricDeltaPresenter.Preference.DOWN_IS_POSITIVE)
        assertEquals(MetricDeltaPresenter.Direction.DOWN, fat.direction)
        assertEquals(MetricDeltaPresenter.Judgement.POSITIVE, fat.judgement)
        assertEquals("${minus}1,2 %", fat.signedText)
        assertEquals("1,2 %", fat.magnitudeText)

        val muscle = MetricDeltaPresenter.present(-0.4f, "kg", MetricDeltaPresenter.Preference.UP_IS_POSITIVE)
        assertEquals(MetricDeltaPresenter.Direction.DOWN, muscle.direction)
        assertEquals(MetricDeltaPresenter.Judgement.NEGATIVE, muscle.judgement)
    }

    @Test
    fun zeroOrRoundedToZeroDeltaIsFlatAndNeutral() {
        listOf(0f, 0.04f, -0.04f).forEach { delta ->
            val weight = MetricDeltaPresenter.present(delta, "kg", MetricDeltaPresenter.Preference.UP_IS_POSITIVE)
            assertEquals(MetricDeltaPresenter.Direction.FLAT, weight.direction)
            assertEquals(MetricDeltaPresenter.Judgement.NEUTRAL, weight.judgement)
            assertEquals("0 kg", weight.signedText)
        }
    }

    @Test
    fun neutralMetricKeepsDirectionWithoutJudgement() {
        val up = MetricDeltaPresenter.present(0.8f, "kg", MetricDeltaPresenter.Preference.NEUTRAL)
        assertEquals(MetricDeltaPresenter.Direction.UP, up.direction)
        assertEquals(MetricDeltaPresenter.Judgement.NEUTRAL, up.judgement)
        assertEquals("+0,8 kg", up.signedText)
    }

    // ---- (1) remaining kcal keeps the overshoot --------------------------------------------------------------

    @Test
    fun remainingAndOvershootAreDistinct() {
        assertEquals(RemainingKcal.Presentation(RemainingKcal.Kind.REMAINING, 500), RemainingKcal.of(2000, 1500))
        assertEquals(RemainingKcal.Presentation(RemainingKcal.Kind.REMAINING, 0), RemainingKcal.of(2000, 2000))
        assertEquals(RemainingKcal.Presentation(RemainingKcal.Kind.OVER, 312), RemainingKcal.of(2000, 2312))
        assertEquals(RemainingKcal.Kind.UNAVAILABLE, RemainingKcal.of(2000, null).kind)
        assertEquals(RemainingKcal.Kind.UNAVAILABLE, RemainingKcal.of(null, 100).kind)
        assertEquals(RemainingKcal.Kind.UNAVAILABLE, RemainingKcal.of(0, 100).kind)
    }

    // ---- (3) weekly expectation uses the profile's TDEE for every day -----------------------------------------

    @Test
    fun weeklyExpectationUsesTheProfileTdeeForEveryDayOfTheWeek() {
        val calculation = calculation(bmr = 1600.0, tdee = 2400.0, target = 2000.0)
        val plannedWeek = (0L until 7L).map { 100L + it to 2000 }
        val metrics = OperationalNutritionMetrics.calculate(calculation)
        val tdee = Math.round(metrics.tdeeKcal!!).toInt()

        val result = WeeklyBodyExpectation.calculate(WeeklyMaintenance.baseKcal(tdee, tdee), 100L, plannedWeek)

        // The home TDEE is the profile's one: no day-specific figure can move the week.
        assertEquals(2400, tdee)
        assertEquals(7 * (2400 - 2000), result.theoreticalDeficitKcal)
        assertEquals(2400, WeeklyMaintenance.baseKcal(2400, 2400))
        assertEquals(2400, WeeklyMaintenance.baseKcal(null, 2400))
        assertNull(WeeklyMaintenance.baseKcal(0, 0))
    }

    // ---- (4) one target for Home, Food and the plan --------------------------------------------------------------

    @Test
    fun planDayTargetWithAdaptationAndRecoveryIsTheTargetEverywhere() {
        // Theoretical (profile + activity) 2068; the plan applied adaptation and recovery: 1.900 kcal, 150 g protein.
        val resolved = DayTargetResolver.resolve(
            theoreticalKcal = 2067.7, theoreticalProteinG = 120.0,
            planDayKcal = 1900, planDayProteinG = 150.0, planWeekKcal = 2000,
        )
        assertEquals(1900, resolved.kcal)
        assertEquals(150.0, resolved.proteinG!!, 0.0)
        assertEquals(DayTargetResolver.Source.PLAN_DAY, resolved.source)
        assertEquals(2068, resolved.theoreticalKcal)
        assertTrue(resolved.theoreticalDiffers)
        // Home and Food call the same function with the same inputs: identical result by construction.
        assertEquals(
            resolved,
            DayTargetResolver.resolve(2067.7, 120.0, 1900, 150.0, 2000),
        )
    }

    @Test
    fun withoutPlanDayTheTheoreticalTargetIsUsedAndLabelled() {
        val theoretical = DayTargetResolver.resolve(1757.5, 100.0, null, null, 1700)
        assertEquals(1758, theoretical.kcal)
        assertEquals(DayTargetResolver.Source.THEORETICAL, theoretical.source)
        assertFalse(theoretical.theoreticalDiffers)
        assertEquals(100.0, theoretical.proteinG!!, 0.0)

        val weekOnly = DayTargetResolver.resolve(null, null, null, null, 1700)
        assertEquals(1700, weekOnly.kcal)
        assertEquals(DayTargetResolver.Source.PLAN_WEEK, weekOnly.source)

        val none = DayTargetResolver.resolve(null, null, null, null, null)
        assertNull(none.kcal)
        assertEquals(DayTargetResolver.Source.NONE, none.source)
    }

    @Test
    fun planDayEqualToTheoreticalDoesNotRepeatIt() {
        val resolved = DayTargetResolver.resolve(2067.7, null, 2068, null, null)
        assertEquals(DayTargetResolver.Source.PLAN_DAY, resolved.source)
        assertFalse(resolved.theoreticalDiffers)
    }

    // ---- (5) plan lookup in week 2..N of a multi-week period -----------------------------------------------------

    @Test
    fun secondWeekOfATwoWeekPeriodFindsThePlanStoredOnTheFirstWeek() = runBlocking {
        val week1 = 20_000L
        val week2 = week1 + 7
        val stored = mapOf(week1 to (week1..(week1 + 13)).toList())
        val loaded = mutableListOf<Long>()
        val load: suspend (Long) -> List<Long>? = { start -> loaded += start; stored[start] }

        val exact = MealPlanWeekSelector.pick(listOf(week1), week1, load) { it }
        val second = MealPlanWeekSelector.pick(listOf(week1), week2, load) { it }
        val third = MealPlanWeekSelector.pick(listOf(week1), week2 + 7, load) { it }

        assertEquals(stored[week1], exact)
        assertEquals("week 2 resolves to the period plan stored on week 1", stored[week1], second)
        assertNull("a week outside the period has no plan", third)
    }

    @Test
    fun exactWeekPlanWinsOverAnOlderPeriodPlan() = runBlocking {
        val week1 = 20_000L
        val week2 = week1 + 7
        val stored = mapOf(week1 to (week1..(week1 + 13)).toList(), week2 to (week2..(week2 + 6)).toList())
        val picked = MealPlanWeekSelector.pick(listOf(week1, week2), week2, { stored[it] }) { it }
        assertEquals(stored[week2], picked)
    }

    // ---- (6) the day rolls over -----------------------------------------------------------------------------------

    @Test
    fun todayFlowEmitsAgainWhenTheDayChanges() = runBlocking {
        val clock = FakeTime(LocalDate.of(2026, 11, 12).atTime(23, 59, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli())
        val waits = mutableListOf<Long>()
        val days = DayChange.todayFlow(clock) { millis -> waits += millis; clock.millis += millis }.take(2).toList()

        assertEquals(listOf(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13)), days)
        assertEquals(listOf(30_000L), waits)
    }

    @Test
    fun todayFlowDoesNotReEmitWithinTheSameDay() = runBlocking {
        val clock = FakeTime(LocalDate.of(2026, 11, 12).atTime(10, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli())
        var waits = 0
        val days = DayChange.todayFlow(clock) { millis ->
            waits++
            // Three minutes pass in the same day, then jump to the next midnight.
            clock.millis += if (waits < 4) millis else DayChange.millisUntilNextDay(clock.millis, clock.zoneId)
        }.take(2).toList()

        assertEquals(listOf(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13)), days)
        assertEquals(4, waits)
    }

    @Test
    fun millisUntilNextDayHonoursTheZone() {
        val zone = ZoneId.of("Europe/Rome")
        val now = LocalDate.of(2026, 11, 12).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(3_600_000L, DayChange.millisUntilNextDay(now, zone))
    }

    private class FakeTime(var millis: Long, override val zoneId: ZoneId = ZoneId.of("UTC")) : TimeProvider {
        override fun nowEpochMillis(): Long = millis
    }

    private fun calculation(bmr: Double, tdee: Double, target: Double) = LocalCalculationEngine.Result(
        bmi = null, bmrKcal = bmr, tdeeKcal = tdee, targetKcal = target,
        proteinG = null, fatG = null, carbsG = null, waistHeightRatio = null, bmrMethod = null,
    )
}
