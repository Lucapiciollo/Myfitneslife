package com.myfitai.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.food.FoodPlanSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(
    activeProfileStore: ActiveProfileStore,
    bodyMeasurements: BodyMeasurementRepository,
    bia: BiaRepository,
    plans: MealPlanRepository,
    cheats: CheatEntryRepository,
) : ViewModel() {

    data class State(
        val profileId: Long? = null,
        val measurements: List<BodyMeasurementEntity> = emptyList(),
        val bia: List<BiaMeasurementEntity> = emptyList(),
        val plans: List<FoodPlanSnapshot> = emptyList(),
        val cheats: List<CheatEntryEntity> = emptyList(),
    )

    val state: StateFlow<State> = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf(State())
        } else {
            combine(
                bodyMeasurements.all(profileId),
                bia.all(profileId),
                plans.plans(profileId),
                cheats.all(profileId),
            ) { measurements, biaRows, planRows, cheatRows ->
                RawState(measurements, biaRows, planRows.map { it.weekStartEpochDay }, cheatRows)
            }.map { raw ->
                State(
                    profileId = profileId,
                    measurements = raw.measurements,
                    bia = raw.bia,
                    plans = raw.planWeeks.mapNotNull { week -> plans.loadLatestSnapshot(profileId, week) },
                    cheats = raw.cheats,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    private data class RawState(
        val measurements: List<BodyMeasurementEntity>,
        val bia: List<BiaMeasurementEntity>,
        val planWeeks: List<Long>,
        val cheats: List<CheatEntryEntity>,
    )

    class Factory(
        private val activeProfileStore: ActiveProfileStore,
        private val bodyMeasurements: BodyMeasurementRepository,
        private val bia: BiaRepository,
        private val plans: MealPlanRepository,
        private val cheats: CheatEntryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HistoryViewModel::class.java))
            return HistoryViewModel(activeProfileStore, bodyMeasurements, bia, plans, cheats) as T
        }
    }
}
