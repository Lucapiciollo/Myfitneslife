package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.domain.food.FoodConsumptionService
import com.myfitai.app.domain.food.FoodConsumptionStatus
import com.myfitai.app.domain.food.FoodConsumptionKeys
import com.myfitai.app.domain.food.FoodMeal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MealDetailViewModel(
    private val repository: MealPlanRepository,
    private val consumptionRepository: FoodConsumptionRepository,
    private val consumptionService: FoodConsumptionService,
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

    fun setStatus(status: FoodConsumptionStatus) {
        val state = _state.value
        val context = state.context ?: return
        viewModelScope.launch {
            runCatching {
                consumptionService.setMealStatus(
                    planId = context.planId,
                    planVersionId = context.planVersionId,
                    dayId = context.dayId,
                    plannedDateEpochDay = context.dateEpochDay,
                    meal = context.meal,
                    status = status,
                )
            }
        }
    }

    fun clearStatus() {
        val context = _state.value.context ?: return
        viewModelScope.launch {
            consumptionService.clear(context.planVersionId, FoodConsumptionKeys.meal(context.meal.id))
        }
    }

    class Factory(
        private val repository: MealPlanRepository,
        private val consumptionRepository: FoodConsumptionRepository,
        private val consumptionService: FoodConsumptionService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MealDetailViewModel::class.java))
            return MealDetailViewModel(repository, consumptionRepository, consumptionService) as T
        }
    }
}
