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

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted = _deleted.asSharedFlow()

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
        val values = listOf(weightKg, bodyFatPercent, visceralFatLevel, muscleMassKg, skeletalMuscleKg, bodyWaterPercent, bmrKcal)
        if (values.all { it == null }) {
            _error.tryEmit("Inserisci almeno un valore BIA")
            return
        }
        if (values.filterNotNull().any { !it.isFinite() || it <= 0f }) {
            _error.tryEmit("I valori BIA devono essere maggiori di zero")
            return
        }
        if (bodyFatPercent != null && bodyFatPercent > 100f) {
            _error.tryEmit("La percentuale di grasso deve essere compresa tra 0 e 100")
            return
        }
        if (bodyWaterPercent != null && bodyWaterPercent > 100f) {
            _error.tryEmit("La percentuale di acqua deve essere compresa tra 0 e 100")
            return
        }
        if (bmrKcal != null && bmrKcal > 10_000f) {
            _error.tryEmit("Controlla il valore BMR")
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

    fun delete(measurement: BiaMeasurementEntity) {
        viewModelScope.launch {
            runCatching { repository.delete(measurement) }
                .onSuccess { _deleted.tryEmit(Unit) }
                .onFailure { _error.tryEmit("Impossibile eliminare la misurazione") }
        }
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
