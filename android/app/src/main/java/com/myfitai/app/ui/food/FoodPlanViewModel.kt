package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class FoodPlanViewModel(
    private val repository: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {

    data class State(
        val weekStart: LocalDate = mondayOf(LocalDate.now()),
        val snapshot: FoodPlanSnapshot? = null,
        val selectedDayIndex: Int = 0,
        val selectedDay: FoodPlanDay? = null,
        val hasPlan: Boolean = false,
    )

    private val selectedWeekStart = MutableStateFlow(mondayOf(LocalDate.now()))
    private val selectedDayIndex = MutableStateFlow(indexForToday(selectedWeekStart.value))

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

    val state: StateFlow<State> = combine(source, selectedDayIndex) { (weekStart, snapshot), dayIndex ->
        val safeIndex = dayIndex.coerceIn(0, 6)
        State(
            weekStart = weekStart,
            snapshot = snapshot,
            selectedDayIndex = safeIndex,
            selectedDay = snapshot?.version?.days?.firstOrNull { it.dateEpochDay == weekStart.plusDays(safeIndex.toLong()).toEpochDay() },
            hasPlan = snapshot != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun previousWeek() {
        selectedWeekStart.value = selectedWeekStart.value.minusWeeks(1)
        selectedDayIndex.value = 0
    }

    fun nextWeek() {
        selectedWeekStart.value = selectedWeekStart.value.plusWeeks(1)
        selectedDayIndex.value = 0
    }

    fun selectDay(index: Int) {
        selectedDayIndex.value = index.coerceIn(0, 6)
    }

    class Factory(
        private val repository: MealPlanRepository,
        private val activeProfileStore: ActiveProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FoodPlanViewModel::class.java))
            return FoodPlanViewModel(repository, activeProfileStore) as T
        }
    }

    companion object {
        private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        private fun indexForToday(weekStart: LocalDate): Int {
            val today = LocalDate.now()
            return if (!today.isBefore(weekStart) && !today.isAfter(weekStart.plusDays(6))) {
                (today.toEpochDay() - weekStart.toEpochDay()).toInt()
            } else 0
        }
    }
}
