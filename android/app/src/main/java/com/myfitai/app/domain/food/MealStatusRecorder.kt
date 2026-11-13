package com.myfitai.app.domain.food

import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.repository.MealPlanRepository

/** Pure rules of the consumed/skipped buttons, shared by the meal detail and the quick actions. */
object MealStatusTransition {
    sealed interface Action {
        data class Set(val status: FoodConsumptionStatus) : Action
        data object Clear : Action
    }

    /** Tapping the status already recorded removes it; tapping the other status switches to it. */
    fun onTap(current: String?, tapped: FoodConsumptionStatus): Action =
        if (current == tapped.name) Action.Clear else Action.Set(tapped)

    /** Action that brings a meal back to [previous] (null or unknown = nothing recorded). */
    fun restore(previous: String?): Action =
        FoodConsumptionStatus.entries.firstOrNull { it.name == previous }?.let(Action::Set) ?: Action.Clear
}

/** Result of a status write; [previous] is what is needed to undo it. */
data class MealStatusChange(val mealId: Long, val previous: String?, val current: String?) {
    enum class Kind { CONSUMED, SKIPPED, CLEARED }

    val changed: Boolean get() = previous != current
    val kind: Kind
        get() = when (current) {
            FoodConsumptionStatus.CONSUMED.name -> Kind.CONSUMED
            FoodConsumptionStatus.SKIPPED.name -> Kind.SKIPPED
            else -> Kind.CLEARED
        }
}

/** Access to the persisted status of a meal; the Room-backed implementation delegates to the existing service. */
interface MealStatusStore {
    suspend fun context(mealId: Long): MealPlanRepository.MealDetailContext?
    suspend fun currentStatus(context: MealPlanRepository.MealDetailContext): String?
    suspend fun set(context: MealPlanRepository.MealDetailContext, status: FoodConsumptionStatus)
    suspend fun clear(context: MealPlanRepository.MealDetailContext)
}

/** Single entry point to record, switch, clear and undo the consumption status of a meal. */
class MealStatusRecorder(private val store: MealStatusStore) {

    /** Same behaviour as the detail buttons: repeating the recorded status clears it. Null if the meal is unknown. */
    suspend fun tap(mealId: Long, tapped: FoodConsumptionStatus): MealStatusChange? {
        val context = store.context(mealId) ?: return null
        val previous = store.currentStatus(context)
        return apply(context, previous, MealStatusTransition.onTap(previous, tapped))
    }

    /** One-tap quick action: never clears. If the meal is already consumed nothing is written ([MealStatusChange.changed] is false). */
    suspend fun markConsumed(mealId: Long): MealStatusChange? {
        val context = store.context(mealId) ?: return null
        val previous = store.currentStatus(context)
        if (previous == FoodConsumptionStatus.CONSUMED.name) return MealStatusChange(mealId, previous, previous)
        return apply(context, previous, MealStatusTransition.Action.Set(FoodConsumptionStatus.CONSUMED))
    }

    /** Brings the meal back to the state it had before [change]. */
    suspend fun restore(change: MealStatusChange) {
        if (!change.changed) return
        val context = store.context(change.mealId) ?: return
        apply(context, change.current, MealStatusTransition.restore(change.previous))
    }

    private suspend fun apply(
        context: MealPlanRepository.MealDetailContext,
        previous: String?,
        action: MealStatusTransition.Action,
    ): MealStatusChange {
        val current = when (action) {
            is MealStatusTransition.Action.Set -> {
                store.set(context, action.status)
                action.status.name
            }
            MealStatusTransition.Action.Clear -> {
                store.clear(context)
                null
            }
        }
        return MealStatusChange(context.meal.id, previous, current)
    }
}

class RoomMealStatusStore(
    private val mealPlans: MealPlanRepository,
    private val consumption: FoodConsumptionRepository,
    private val service: FoodConsumptionService,
    private val activeProfileStore: ActiveProfileStore,
) : MealStatusStore {
    override suspend fun context(mealId: Long): MealPlanRepository.MealDetailContext? {
        val profileId = activeProfileStore.currentIdOrNull() ?: return null
        return mealPlans.getMealContext(profileId, mealId)
    }

    override suspend fun currentStatus(context: MealPlanRepository.MealDetailContext): String? {
        val profileId = activeProfileStore.currentIdOrNull() ?: return null
        return consumption.getForItem(profileId, context.planVersionId, FoodConsumptionKeys.meal(context.meal.id))?.status
    }

    override suspend fun set(context: MealPlanRepository.MealDetailContext, status: FoodConsumptionStatus) {
        service.setMealStatus(
            planId = context.planId,
            planVersionId = context.planVersionId,
            dayId = context.dayId,
            plannedDateEpochDay = context.dateEpochDay,
            meal = context.meal,
            status = status,
        )
    }

    override suspend fun clear(context: MealPlanRepository.MealDetailContext) {
        service.clear(context.planVersionId, FoodConsumptionKeys.meal(context.meal.id))
    }
}
