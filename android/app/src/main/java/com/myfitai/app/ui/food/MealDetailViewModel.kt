package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.domain.food.FoodConsumptionStatus
import com.myfitai.app.domain.food.FoodConsumptionKeys
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.MealStatusChange
import com.myfitai.app.domain.food.MealStatusRecorder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MealDetailViewModel(
    private val repository: MealPlanRepository,
    private val consumptionRepository: FoodConsumptionRepository,
    private val statusRecorder: MealStatusRecorder,
) : ViewModel() {

    data class State(
        val loading: Boolean = false,
        val context: MealPlanRepository.MealDetailContext? = null,
        val consumption: FoodConsumptionEntity? = null,
        val error: String? = null,
    ) {
        val meal: FoodMeal? get() = context?.meal
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(profileId: Long, mealId: Long) {
        if (mealId <= 0L) {
            _state.value = State(error = "Pasto non valido")
            return
        }
        viewModelScope.launch {
            _state.value = State(loading = true)
            try {
                val context = repository.getMealContext(profileId, mealId)
                if (context == null) {
                    _state.value = State(error = "Pasto non trovato")
                } else {
                    consumptionRepository.observeForItem(profileId, context.planVersionId, FoodConsumptionKeys.meal(context.meal.id))
                        .collect { consumption -> _state.value = State(context = context, consumption = consumption) }
                }
            } catch (_: Throwable) {
                _state.value = State(error = "Impossibile caricare il pasto")
            }
        }
    }

    private val _changes = MutableSharedFlow<MealStatusChange>(extraBufferCapacity = 1)

    /** Emits every recorded change that actually altered the status, so the screen can confirm it and offer Undo. */
    val changes: SharedFlow<MealStatusChange> = _changes.asSharedFlow()

    /** Same rule as before: tapping the status already recorded removes it, otherwise it is recorded. */
    fun tapStatus(status: FoodConsumptionStatus) {
        val mealId = _state.value.context?.meal?.id ?: return
        viewModelScope.launch {
            runCatching { statusRecorder.tap(mealId, status) }.getOrNull()
                ?.takeIf { it.changed }
                ?.let { _changes.tryEmit(it) }
        }
    }

    fun undo(change: MealStatusChange) {
        viewModelScope.launch { runCatching { statusRecorder.restore(change) } }
    }

    class Factory(
        private val repository: MealPlanRepository,
        private val consumptionRepository: FoodConsumptionRepository,
        private val statusRecorder: MealStatusRecorder,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MealDetailViewModel::class.java))
            return MealDetailViewModel(repository, consumptionRepository, statusRecorder) as T
        }
    }
}
