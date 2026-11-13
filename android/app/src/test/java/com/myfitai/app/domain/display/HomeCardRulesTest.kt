package com.myfitai.app.domain.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX5a: pure rules behind the Home card (quick consume, weekly-estimate note, merged consumption note). */
class HomeCardRulesTest {

    // ---- Quick "segna come consumato": only today or past ----

    @Test
    fun quickConsume_isHiddenForFutureDays() {
        assertEquals(QuickConsumeRule.State.HIDDEN, QuickConsumeRule.stateOf(mealDateEpochDay = 101, todayEpochDay = 100, alreadyConsumed = false))
        assertEquals(QuickConsumeRule.State.HIDDEN, QuickConsumeRule.stateOf(mealDateEpochDay = 101, todayEpochDay = 100, alreadyConsumed = true))
    }

    @Test
    fun quickConsume_isAvailableForTodayAndPast() {
        assertEquals(QuickConsumeRule.State.AVAILABLE, QuickConsumeRule.stateOf(100, 100, alreadyConsumed = false))
        assertEquals(QuickConsumeRule.State.AVAILABLE, QuickConsumeRule.stateOf(99, 100, alreadyConsumed = false))
    }

    @Test
    fun quickConsume_showsDoneWhenAlreadyConsumed() {
        assertEquals(QuickConsumeRule.State.DONE, QuickConsumeRule.stateOf(100, 100, alreadyConsumed = true))
        assertEquals(QuickConsumeRule.State.DONE, QuickConsumeRule.stateOf(99, 100, alreadyConsumed = true))
    }

    // ---- Note under the weekly estimate ----

    @Test
    fun weeklyEstimateNote_onlyWhenUpdatePendingAndTheEstimateUsesAPlan() {
        assertTrue(WeeklyEstimateNote.shouldShow(planUpdatePending = true, plannedDays = 4))
        assertFalse(WeeklyEstimateNote.shouldShow(planUpdatePending = false, plannedDays = 4))
        // No planned day: there is no old plan the estimate could be using.
        assertFalse(WeeklyEstimateNote.shouldShow(planUpdatePending = true, plannedDays = 0))
        assertFalse(WeeklyEstimateNote.shouldShow(planUpdatePending = false, plannedDays = 0))
    }

    // ---- One merged note under the rings ----

    @Test
    fun consumptionNote_picksTheSameWordingAsBefore() {
        assertEquals(ConsumptionNote.Kind.ALL_SKIPPED, ConsumptionNote.kindOf(consumedCount = 0, recordedCount = 2))
        assertEquals(ConsumptionNote.Kind.NONE_REGISTERED, ConsumptionNote.kindOf(consumedCount = 0, recordedCount = 0))
        assertEquals(ConsumptionNote.Kind.PARTIAL, ConsumptionNote.kindOf(consumedCount = 2, recordedCount = 3))
        assertEquals(ConsumptionNote.Kind.ALL_CONSUMED, ConsumptionNote.kindOf(consumedCount = 3, recordedCount = 3))
    }
}
