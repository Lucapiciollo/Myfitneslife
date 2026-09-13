package com.myfitai.app.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.shopping.ShoppingListEngine
import com.myfitai.app.domain.shopping.ShoppingListStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class ShoppingListViewModel(
    private val plans: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val stateStore: ShoppingListStateStore,
    initialWeekStartEpochDay: Long?,
) : ViewModel() {

    enum class ViewMode { WEEK, CATEGORY }
    enum class Filter { ALL, TO_BUY, PURCHASED, PANTRY }

    data class Row(
        val item: ShoppingListEngine.Item,
        val status: ShoppingListStateStore.Status,
    )

    data class State(
        val loading: Boolean = true,
        val profileId: Long? = null,
        val weekStartEpochDay: Long = defaultMonday().toEpochDay(),
        val versionNumber: Int? = null,
        val rows: List<Row> = emptyList(),
        val viewMode: ViewMode = ViewMode.WEEK,
        val filter: Filter = Filter.ALL,
        val error: String? = null,
    ) {
        val visibleRows: List<Row>
            get() = rows.filter { row ->
                when (filter) {
                    Filter.ALL -> true
                    Filter.TO_BUY -> row.status == ShoppingListStateStore.Status.TO_BUY
                    Filter.PURCHASED -> row.status == ShoppingListStateStore.Status.PURCHASED
                    Filter.PANTRY -> row.status == ShoppingListStateStore.Status.PANTRY
                }
            }
    }

    private val _state = MutableStateFlow(
        State(weekStartEpochDay = initialWeekStartEpochDay ?: defaultMonday().toEpochDay())
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        reload()
    }

    fun setViewMode(index: Int) {
        _state.value = _state.value.copy(viewMode = if (index == 1) ViewMode.CATEGORY else ViewMode.WEEK)
    }

    fun setFilter(index: Int) {
        _state.value = _state.value.copy(
            filter = when (index) {
                1 -> Filter.TO_BUY
                2 -> Filter.PURCHASED
                3 -> Filter.PANTRY
                else -> Filter.ALL
            }
        )
    }

    fun setStatus(itemKey: String, status: ShoppingListStateStore.Status) {
        val current = _state.value
        val profileId = current.profileId ?: return
        stateStore.setStatus(profileId, current.weekStartEpochDay, itemKey, status)
        _state.value = current.copy(rows = current.rows.map { row ->
            if (row.item.key == itemKey) row.copy(status = status) else row
        })
    }

    fun togglePurchased(itemKey: String, checked: Boolean) {
        setStatus(itemKey, if (checked) ShoppingListStateStore.Status.PURCHASED else ShoppingListStateStore.Status.TO_BUY)
    }

    fun resetStatuses() {
        val current = _state.value
        val profileId = current.profileId ?: return
        stateStore.reset(profileId, current.weekStartEpochDay, current.rows.map { it.item.key })
        _state.value = current.copy(rows = current.rows.map { it.copy(status = ShoppingListStateStore.Status.TO_BUY) })
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val profileId = activeProfileStore.currentIdOrNull()
            if (profileId == null) {
                _state.value = _state.value.copy(loading = false, error = "Nessun profilo attivo")
                return@launch
            }
            val week = _state.value.weekStartEpochDay
            val snapshot = plans.loadLatestSnapshot(profileId, week)
            if (snapshot == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    profileId = profileId,
                    versionNumber = null,
                    rows = emptyList(),
                    error = null,
                )
                return@launch
            }
            val items = ShoppingListEngine.aggregate(snapshot)
            _state.value = _state.value.copy(
                loading = false,
                profileId = profileId,
                versionNumber = snapshot.version.versionNumber,
                rows = items.map { item ->
                    Row(item, stateStore.status(profileId, week, item.key))
                },
                error = null,
            )
        }
    }

    class Factory(
        private val plans: MealPlanRepository,
        private val activeProfileStore: ActiveProfileStore,
        private val stateStore: ShoppingListStateStore,
        private val initialWeekStartEpochDay: Long?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ShoppingListViewModel::class.java))
            return ShoppingListViewModel(plans, activeProfileStore, stateStore, initialWeekStartEpochDay) as T
        }
    }

    companion object {
        private fun defaultMonday(): LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}