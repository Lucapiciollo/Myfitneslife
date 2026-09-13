package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private fun planWeekMonday(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun todayIndexInWeek(weekStart: LocalDate): Int {
    val today = LocalDate.now()
    return if (!today.isBefore(weekStart) && !today.isAfter(weekStart.plusDays(6))) {
        (today.toEpochDay() - weekStart.toEpochDay()).toInt()
    } else 0
}

class FoodPlanViewModel(
    private val repository: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val generationService: NutritionPlanGenerationService,
) : ViewModel() {

    data class GenerationState(
        val running: Boolean = false,
        val error: String? = null,
        val successMessage: String? = null,
    )

    data class State(
        val weekStart: LocalDate = planWeekMonday(LocalDate.now()),
        val snapshot: FoodPlanSnapshot? = null,
        val selectedDayIndex: Int = 0,
        val selectedDay: FoodPlanDay? = null,
        val hasPlan: Boolean = false,
        val generation: GenerationState = GenerationState(),
    )

    private val selectedWeekStart = MutableStateFlow(planWeekMonday(LocalDate.now()))
    private val selectedDayIndex = MutableStateFlow(todayIndexInWeek(selectedWeekStart.value))
    private val generationState = MutableStateFlow(GenerationState())

    private val source = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf<Pair<LocalDate, FoodPlanSnapshot?>>(selectedWeekStart.value to null)
        } else {
            selectedWeekStart.flatMapLatest { weekStart ->
                repository.plans(profileId).flatMapLatest {
                    flow {
                        emit(weekStart to repository.loadLatestSnapshot(profileId, weekStart.toEpochDay()))
                    }
                }
            }
        }
    }

    val state: StateFlow<State> = combine(source, selectedDayIndex, generationState) { (weekStart, snapshot), dayIndex, generation ->
        val safeIndex = dayIndex.coerceIn(0, 6)
        State(
            weekStart = weekStart,
            snapshot = snapshot,
            selectedDayIndex = safeIndex,
            selectedDay = snapshot?.version?.days?.firstOrNull {
                it.dateEpochDay == weekStart.plusDays(safeIndex.toLong()).toEpochDay()
            },
            hasPlan = snapshot != null,
            generation = generation,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun previousWeek() {
        if (generationState.value.running) return
        selectedWeekStart.value = selectedWeekStart.value.minusWeeks(1)
        selectedDayIndex.value = 0
        clearGenerationMessage()
    }

    fun nextWeek() {
        if (generationState.value.running) return
        selectedWeekStart.value = selectedWeekStart.value.plusWeeks(1)
        selectedDayIndex.value = 0
        clearGenerationMessage()
    }

    fun selectDay(index: Int) {
        selectedDayIndex.value = index.coerceIn(0, 6)
    }

    fun generateCurrentWeek() {
        if (generationState.value.running) return
        val week = selectedWeekStart.value
        generationState.value = GenerationState(running = true)
        viewModelScope.launch {
            runCatching { generationService.generateWeek(week) }
                .onSuccess { result ->
                    generationState.value = GenerationState(
                        successMessage = "Piano generato con ${result.provider} · ${result.model}",
                    )
                }
                .onFailure { error ->
                    val message = when (error) {
                        is NutritionPlanGenerationService.GenerationException.NeedsInput ->
                            "Completa prima: ${error.fields.joinToString()}"
                        is NutritionPlanGenerationService.GenerationException.PastWeek ->
                            "Le settimane concluse sono storico in sola lettura."
                        else -> error.message ?: "Generazione non riuscita"
                    }
                    generationState.value = GenerationState(error = message)
                }
        }
    }

    fun clearGenerationMessage() {
        if (!generationState.value.running) generationState.value = GenerationState()
    }

    class Factory(
        private val repository: MealPlanRepository,
        private val activeProfileStore: ActiveProfileStore,
        private val generationService: NutritionPlanGenerationService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FoodPlanViewModel::class.java))
            return FoodPlanViewModel(repository, activeProfileStore, generationService) as T
        }
    }
}
