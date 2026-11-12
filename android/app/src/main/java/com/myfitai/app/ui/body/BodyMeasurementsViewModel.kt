package com.myfitai.app.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.domain.food.NutritionPathTrigger
import com.myfitai.app.domain.body.BodyValueRanges
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BodyMeasurementsViewModel(
    private val repository: BodyMeasurementRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val nutritionPathTrigger: NutritionPathTrigger? = null,
) : ViewModel() {

    val measurements: StateFlow<List<BodyMeasurementEntity>> = activeProfileStore.activeProfileId
        .flatMapLatest { profileId ->
            if (profileId > 0) repository.all(profileId) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private val _goalAdvice = MutableSharedFlow<NutritionPathTrigger.Advice>(extraBufferCapacity = 1)

    /** Optional invite for a goal advice after saving (see [NutritionPathTrigger.Advice]); never navigates by itself. */
    val goalAdvice: SharedFlow<NutritionPathTrigger.Advice> = _goalAdvice.asSharedFlow()

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
        hipsCm: Float? = null,
        weightKg: Float? = null,
        existingMeasurementId: Long? = null,
    ) {
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            _error.tryEmit("Nessun profilo attivo")
            return
        }

        val values = listOf(
            chestCm, waistCm, abdomenCm, shouldersCm, glutesCm,
            armLeftCm, armRightCm, thighLeftCm, thighRightCm, calfLeftCm, calfRightCm, hipsCm, weightKg,
        )
        if (values.all { it == null }) {
            _error.tryEmit("Inserisci almeno una misura")
            return
        }
        val circumferences = listOf(
            "Torace" to chestCm, "Vita" to waistCm, "Addome" to abdomenCm, "Spalle" to shouldersCm, "Glutei" to glutesCm,
            "Braccio sinistro" to armLeftCm, "Braccio destro" to armRightCm, "Coscia sinistra" to thighLeftCm,
            "Coscia destra" to thighRightCm, "Polpaccio sinistro" to calfLeftCm, "Polpaccio destro" to calfRightCm,
            "Fianchi" to hipsCm,
        )
        BodyValueRanges.validateMeasurements(circumferences, weightKg)?.let {
            _error.tryEmit(it)
            return
        }

        viewModelScope.launch {
            runCatching {
                val existing = existingMeasurementId?.let { id ->
                    repository.all(profileId).first().firstOrNull { it.id == id }
                        ?: error("Rilevazione non disponibile per il profilo attivo")
                }
                val measurement = BodyMeasurementEntity(
                        id = existing?.id ?: 0L,
                        notes = existing?.notes,
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
                        hipsCm = hipsCm,
                        weightKg = weightKg,
                    )
                if (existing != null) repository.update(measurement) else repository.insert(measurement)
            }.onSuccess {
                // No paid AI job here: only the optional invite (or the "queued" notice if the explicit automation is on).
                runCatching { nutritionPathTrigger?.onMeasurementsChanged(profileId) }.getOrNull()
                    ?.let { _goalAdvice.tryEmit(it) }
                _saved.tryEmit(Unit)
            }.onFailure {
                _error.tryEmit("Impossibile salvare o aggiornare la misurazione")
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
        private val nutritionPathTrigger: NutritionPathTrigger? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BodyMeasurementsViewModel::class.java))
            return BodyMeasurementsViewModel(repository, activeProfileStore, nutritionPathTrigger) as T
        }
    }
}
