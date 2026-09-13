package com.myfitai.app.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BodyMeasurementRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BodyMeasurementsViewModel(
    private val repository: BodyMeasurementRepository,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {

    val measurements: StateFlow<List<BodyMeasurementEntity>> = activeProfileStore.activeProfileId
        .flatMapLatest { profileId ->
            if (profileId > 0) repository.all(profileId) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    fun save(
        measuredAtEpochMillis: Long,
        chestCm: Float?,
        waistCm: Float?,
        abdomenCm: Float?,
        shouldersCm: Float?,
        glutesCm: Float?,
        armLeftCm: Float?,
        armRightCm: Float?,
        thighLeftCm: Float?,
        thighRightCm: Float?,
        calfLeftCm: Float?,
        calfRightCm: Float?,
    ) {
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            _error.tryEmit("Nessun profilo attivo")
            return
        }

        val values = listOf(
            chestCm, waistCm, abdomenCm, shouldersCm, glutesCm,
            armLeftCm, armRightCm, thighLeftCm, thighRightCm, calfLeftCm, calfRightCm,
        )
        if (values.all { it == null }) {
            _error.tryEmit("Inserisci almeno una misura")
            return
        }
        if (values.filterNotNull().any { it <= 0f || it > 300f }) {
            _error.tryEmit("Controlla i valori inseriti")
            return
        }

        viewModelScope.launch {
            runCatching {
                repository.insert(
                    BodyMeasurementEntity(
                        profileId = profileId,
                        measuredAtEpochMillis = measuredAtEpochMillis,
                        chestCm = chestCm,
                        waistCm = waistCm,
                        abdomenCm = abdomenCm,
                        shouldersCm = shouldersCm,
                        glutesCm = glutesCm,
                        armLeftCm = armLeftCm,
                        armRightCm = armRightCm,
                        thighLeftCm = thighLeftCm,
                        thighRightCm = thighRightCm,
                        calfLeftCm = calfLeftCm,
                        calfRightCm = calfRightCm,
                    )
                )
            }.onSuccess {
                _saved.tryEmit(Unit)
            }.onFailure {
                _error.tryEmit("Impossibile salvare la misurazione")
            }
        }
    }

    fun delete(measurement: BodyMeasurementEntity) {
        viewModelScope.launch {
            runCatching { repository.delete(measurement) }
                .onSuccess { _deleted.tryEmit(Unit) }
                .onFailure { _error.tryEmit("Impossibile eliminare la misurazione") }
        }
    }

    class Factory(
        private val repository: BodyMeasurementRepository,
        private val activeProfileStore: ActiveProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BodyMeasurementsViewModel::class.java))
            return BodyMeasurementsViewModel(repository, activeProfileStore) as T
        }
    }
}
