package com.myfitai.app.ui.food

import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanMetrics
import com.myfitai.app.ui.food.FoodPlanPresentation.Inputs
import com.myfitai.app.ui.food.FoodPlanPresentation.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodPlanPresentationTest {

    private fun inputs(
        hasPlan: Boolean = true,
        currentWeek: Boolean = true,
        periodCanBeGenerated: Boolean = true,
        running: Boolean = false,
        error: Boolean = false,
        success: Boolean = false,
        ageInvalid: Boolean = false,
        goalChanged: Boolean = false,
        hasDayContent: Boolean = true,
        scheduleActive: Boolean = false,
    ) = Inputs(hasPlan, currentWeek, periodCanBeGenerated, running, error, success, ageInvalid, goalChanged, hasDayContent, scheduleActive)

    @Test
    fun withoutPlan_showsEmptyCardWithCtaAndHidesMealCount() {
        val v = FoodPlanPresentation.resolve(inputs(hasPlan = false, hasDayContent = false))
        assertTrue(v.emptyCard)
        assertTrue(v.generateInEmptyCard)
        assertFalse(v.mealCountHint)
        assertFalse(v.mealsCard)
    }

    @Test
    fun withPlan_hidesEmptyCardAndKeepsGenerateAtBottom() {
        val v = FoodPlanPresentation.resolve(inputs(hasPlan = true))
        assertFalse(v.emptyCard)
        assertFalse(v.generateInEmptyCard)
        assertTrue(v.mealCountHint)
        assertTrue(v.mealsCard)
    }

    @Test
    fun mealsCardFollowsDayContentOnly_statusMessagesLiveInBanners() {
        val v = FoodPlanPresentation.resolve(inputs(hasDayContent = false, running = true, goalChanged = true))
        assertFalse(v.mealsCard)
        assertTrue(v.statusBanner)
        assertTrue(v.goalChangedBanner)
    }

    @Test
    fun goalChangedBanner_isShownOnlyForCurrentWeek_andNotDuplicatedInStatus() {
        val current = FoodPlanPresentation.resolve(inputs(goalChanged = true, currentWeek = true))
        assertTrue(current.goalChangedBanner)
        assertEquals(StatusKind.NONE, current.statusKind)
        assertFalse(current.statusBanner)

        val other = FoodPlanPresentation.resolve(inputs(goalChanged = true, currentWeek = false))
        assertFalse(other.goalChangedBanner)
        assertEquals(StatusKind.GOAL_CHANGED, other.statusKind)
        assertTrue(other.statusBanner)
    }

    @Test
    fun statusPriority_runningThenErrorThenAgeThenSuccess() {
        assertEquals(StatusKind.RUNNING, FoodPlanPresentation.resolve(inputs(running = true, error = true, success = true, ageInvalid = true)).statusKind)
        assertEquals(StatusKind.ERROR, FoodPlanPresentation.resolve(inputs(error = true, success = true, ageInvalid = true)).statusKind)
        assertEquals(StatusKind.AGE_WARNING, FoodPlanPresentation.resolve(inputs(success = true, ageInvalid = true)).statusKind)
        assertEquals(StatusKind.SUCCESS, FoodPlanPresentation.resolve(inputs(success = true)).statusKind)
        assertEquals(StatusKind.NONE, FoodPlanPresentation.resolve(inputs()).statusKind)
    }

    @Test
    fun statusBanner_isHiddenWhenPeriodCannotBeGenerated() {
        val v = FoodPlanPresentation.resolve(inputs(periodCanBeGenerated = false, error = true))
        assertEquals(StatusKind.ERROR, v.statusKind)
        assertFalse(v.statusBanner)
    }

    @Test
    fun scheduleStatus_isShownOnlyWhenScheduleIsActive() {
        assertFalse(FoodPlanPresentation.resolve(inputs(scheduleActive = false)).scheduleStatus)
        assertTrue(FoodPlanPresentation.resolve(inputs(scheduleActive = true)).scheduleStatus)
    }

    @Test
    fun macroColumns_useDeclaredMealValuesAndStayHiddenWithoutData() {
        val withMacros = FoodPlanMetrics.dayTotals(
            day(
                FoodMeal(1, 1, 0, "Pranzo", "Pasto", null, 700, 40f, 80f, 20f, null, emptyList()),
                FoodMeal(2, 1, 1, "Cena", "Pasto", null, 800, 50f, 90f, 25f, null, emptyList()),
            ),
        )
        assertEquals(170.0, withMacros.carbsG!!, 0.001)
        assertEquals(45.0, withMacros.fatG!!, 0.001)
        assertTrue(FoodPlanPresentation.macroColumnsVisible(withMacros))

        val withoutMacros = FoodPlanMetrics.dayTotals(
            day(FoodMeal(1, 1, 0, "Pranzo", "Pasto", null, 700, null, null, null, null, emptyList())),
        )
        assertFalse(FoodPlanPresentation.macroColumnsVisible(withoutMacros))
    }

    private fun day(vararg meals: FoodMeal) = FoodPlanDay(
        id = 1,
        dateEpochDay = 1,
        totalKcal = null,
        proteinG = null,
        carbsG = null,
        fatG = null,
        meals = meals.toList(),
    )
}
