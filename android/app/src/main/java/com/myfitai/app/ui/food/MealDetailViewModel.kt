package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.food.FoodMeal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MealDetailViewModel(
    private val repository: MealPlanRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = false,
        val meal: FoodMeal? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(profileId: Long, mealId: Long) {
        if (mealId <= 0L) {
            _state.value = State(error = "Pasto non valido")
            return
        }
        viewModelScope.launch {
            _state.value = State(loading = true)
            runCatching { repository.getMealDetail(profileId, mealId) }
                .onSuccess { meal ->
                    _state.value = if (meal != null) State(meal = meal) else State(error = "Pasto non trovato")
                }
                .onFailure {
                    _state.value = State(error = "Impossibile caricare il pasto")
                }
        }
    }

    class Factory(private val repository: MealPlanRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MealDetailViewModel::class.java))
            return MealDetailViewModel(repository) as T
        }
    }
}
