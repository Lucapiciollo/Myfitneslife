package com.myfitai.app.domain.food

import com.myfitai.app.data.repository.MealPlanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealStatusRecorderTest {

    private class FakeStore(private val knownMealIds: Set<Long> = setOf(MEAL_ID)) : MealStatusStore {
        val statuses = mutableMapOf<Long, String>()
        val writes = mutableListOf<String>()

        override suspend fun context(mealId: Long): MealPlanRepository.MealDetailContext? =
            if (mealId in knownMealIds) context(mealId, planVersionId = 7L) else null

        override suspend fun currentStatus(context: MealPlanRepository.MealDetailContext): String? = statuses[context.meal.id]

        override suspend fun set(context: MealPlanRepository.MealDetailContext, status: FoodConsumptionStatus) {
            statuses[context.meal.id] = status.name
            writes += "set:${status.name}"
        }

        override suspend fun clear(context: MealPlanRepository.MealDetailContext) {
            statuses.remove(context.meal.id)
            writes += "clear"
        }
    }

    // ---- MealStatusTransition (pure) ----

    @Test
    fun tap_onUnrecorded_setsTappedStatus() {
        assertEquals(MealStatusTransition.Action.Set(FoodConsumptionStatus.CONSUMED), MealStatusTransition.onTap(null, FoodConsumptionStatus.CONSUMED))
        assertEquals(MealStatusTransition.Action.Set(FoodConsumptionStatus.SKIPPED), MealStatusTransition.onTap(null, FoodConsumptionStatus.SKIPPED))
    }

    @Test
    fun tap_onSameStatus_clears() {
        assertEquals(MealStatusTransition.Action.Clear, MealStatusTransition.onTap("CONSUMED", FoodConsumptionStatus.CONSUMED))
        assertEquals(MealStatusTransition.Action.Clear, MealStatusTransition.onTap("SKIPPED", FoodConsumptionStatus.SKIPPED))
    }

    @Test
    fun tap_onOtherStatus_switchesWithoutClearing() {
        assertEquals(MealStatusTransition.Action.Set(FoodConsumptionStatus.CONSUMED), MealStatusTransition.onTap("SKIPPED", FoodConsumptionStatus.CONSUMED))
        assertEquals(MealStatusTransition.Action.Set(FoodConsumptionStatus.SKIPPED), MealStatusTransition.onTap("CONSUMED", FoodConsumptionStatus.SKIPPED))
    }

    @Test
    fun restore_mapsPreviousStatusToAction() {
        assertEquals(MealStatusTransition.Action.Clear, MealStatusTransition.restore(null))
        assertEquals(MealStatusTransition.Action.Clear, MealStatusTransition.restore("UNKNOWN"))
        assertEquals(MealStatusTransition.Action.Set(FoodConsumptionStatus.SKIPPED), MealStatusTransition.restore("SKIPPED"))
        assertEquals(MealStatusTransition.Action.Set(FoodConsumptionStatus.CONSUMED), MealStatusTransition.restore("CONSUMED"))
    }

    @Test
    fun change_describesWhatHappened() {
        assertEquals(MealStatusChange.Kind.CONSUMED, MealStatusChange(1L, null, "CONSUMED").kind)
        assertEquals(MealStatusChange.Kind.SKIPPED, MealStatusChange(1L, "CONSUMED", "SKIPPED").kind)
        assertEquals(MealStatusChange.Kind.CLEARED, MealStatusChange(1L, "CONSUMED", null).kind)
        assertTrue(MealStatusChange(1L, null, "CONSUMED").changed)
        assertFalse(MealStatusChange(1L, "CONSUMED", "CONSUMED").changed)
    }

    // ---- MealStatusRecorder (use case shared by detail and quick actions) ----

    @Test
    fun tap_recordsAndReturnsChangeWithPreviousStatus() = runBlocking {
        val store = FakeStore()
        val recorder = MealStatusRecorder(store)

        val change = recorder.tap(MEAL_ID, FoodConsumptionStatus.CONSUMED)!!

        assertNull(change.previous)
        assertEquals("CONSUMED", change.current)
        assertEquals("CONSUMED", store.statuses[MEAL_ID])
    }

    @Test
    fun tap_twiceOnSameStatus_clearsLikeTheDetailButtonDid() = runBlocking {
        val store = FakeStore()
        val recorder = MealStatusRecorder(store)
        recorder.tap(MEAL_ID, FoodConsumptionStatus.SKIPPED)

        val change = recorder.tap(MEAL_ID, FoodConsumptionStatus.SKIPPED)!!

        assertEquals("SKIPPED", change.previous)
        assertNull(change.current)
        assertNull(store.statuses[MEAL_ID])
    }

    @Test
    fun markConsumed_isOneTapAndSetsConsumed() = runBlocking {
        val store = FakeStore()
        val recorder = MealStatusRecorder(store)

        val change = recorder.markConsumed(MEAL_ID)!!

        assertTrue(change.changed)
        assertEquals(MealStatusChange.Kind.CONSUMED, change.kind)
        assertEquals(listOf("set:CONSUMED"), store.writes)
    }

    @Test
    fun markConsumed_onAlreadyConsumed_neverClearsAndWritesNothing() = runBlocking {
        val store = FakeStore().apply { statuses[MEAL_ID] = "CONSUMED" }
        val recorder = MealStatusRecorder(store)

        val change = recorder.markConsumed(MEAL_ID)!!

        assertFalse(change.changed)
        assertEquals("CONSUMED", store.statuses[MEAL_ID])
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun markConsumed_overSkipped_switchesAndUndoRestoresSkipped() = runBlocking {
        val store = FakeStore().apply { statuses[MEAL_ID] = "SKIPPED" }
        val recorder = MealStatusRecorder(store)

        val change = recorder.markConsumed(MEAL_ID)!!
        assertEquals("CONSUMED", store.statuses[MEAL_ID])

        recorder.restore(change)

        assertEquals("SKIPPED", store.statuses[MEAL_ID])
    }

    @Test
    fun restore_ofFirstRecording_removesTheRecord() = runBlocking {
        val store = FakeStore()
        val recorder = MealStatusRecorder(store)
        val change = recorder.markConsumed(MEAL_ID)!!

        recorder.restore(change)

        assertNull(store.statuses[MEAL_ID])
        assertEquals(listOf("set:CONSUMED", "clear"), store.writes)
    }

    @Test
    fun restore_ofUnchangedChange_doesNotWrite() = runBlocking {
        val store = FakeStore().apply { statuses[MEAL_ID] = "CONSUMED" }
        val recorder = MealStatusRecorder(store)

        recorder.restore(MealStatusChange(MEAL_ID, "CONSUMED", "CONSUMED"))

        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun unknownMeal_returnsNullAndWritesNothing() = runBlocking {
        val store = FakeStore()
        val recorder = MealStatusRecorder(store)

        assertNull(recorder.markConsumed(999L))
        assertNull(recorder.tap(999L, FoodConsumptionStatus.SKIPPED))
        assertTrue(store.writes.isEmpty())
    }

    private companion object {
        const val MEAL_ID = 42L

        fun context(mealId: Long, planVersionId: Long) = MealPlanRepository.MealDetailContext(
            planId = 1L,
            planVersionId = planVersionId,
            dayId = 3L,
            dateEpochDay = 20_000L,
            meal = FoodMeal(
                id = mealId, dayId = 3L, sortOrder = 0, type = "Pranzo", title = "Riso e pollo", timeMinutes = 13 * 60,
                kcal = 700, proteinG = 50f, carbsG = 80f, fatG = 18f, preparation = null, ingredients = emptyList(),
            ),
        )
    }
}
