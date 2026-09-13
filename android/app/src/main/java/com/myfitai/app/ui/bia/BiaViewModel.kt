package com.myfitai.app.ui.bia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BiaViewModel(
    private val repository: BiaRepository,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {

    val history: StateFlow<List<BiaMeasurementEntity>> = activeProfileStore.activeProfileId
        .filter { it > 0 }
        .flatMapLatest { repository.all(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error = _error.asSharedFlow()

    fun save(
        measuredAtEpochMillis: Long,
        weightKg: Float?,
        bodyFatPercent: Float?,
        visceralFatLevel: Float?,
        muscleMassKg: Float?,
        skeletalMuscleKg: Float?,
        bodyWaterPercent: Float?,
        bmrKcal: Float?,
        fasting: Boolean,
        justWokeUp: Boolean,
        afterBathroom: Boolean,
        noRecentWorkout: Boolean,
    ) {
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            _error.tryEmit("Nessun profilo attivo")
            return
        }
        if (listOf(weightKg, bodyFatPercent, visceralFatLevel, muscleMassKg, skeletalMuscleKg, bodyWaterPercent, bmrKcal).all { it == null }) {
            _error.tryEmit("Inserisci almeno un valore BIA")
            return
        }

        viewModelScope.launch {
            runCatching {
                repository.insert(
                    BiaMeasurementEntity(
                        profileId = profileId,
                        measuredAtEpochMillis = measuredAtEpochMillis,
                        weightKg = weightKg,
                        bodyFatPercent = bodyFatPercent,
                        visceralFatLevel = visceralFatLevel,
                        muscleMassKg = muscleMassKg,
                        skeletalMuscleKg = skeletalMuscleKg,
                        bodyWaterPercent = bodyWaterPercent,
                        bmrKcal = bmrKcal,
                        fasting = fasting,
                        justWokeUp = justWokeUp,
                        afterBathroom = afterBathroom,
                        noRecentWorkout = noRecentWorkout,
                    )
                )
            }.onSuccess { _saved.tryEmit(Unit) }
                .onFailure { _error.tryEmit("Impossibile salvare la misurazione BIA") }
        }
    }

    suspend fun delete(measurement: BiaMeasurementEntity) {
        runCatching { repository.delete(measurement) }
            .onFailure { _error.tryEmit("Impossibile eliminare la misurazione") }
    }

    class Factory(
        private val repository: BiaRepository,
        private val activeProfileStore: ActiveProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BiaViewModel::class.java))
            return BiaViewModel(repository, activeProfileStore) as T
        }
    }
}
