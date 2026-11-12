package com.myfitai.app.ui.bia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.domain.food.NutritionPathTrigger
import com.myfitai.app.domain.body.BodyValueRanges
import com.myfitai.app.domain.body.BiaProgressCoachScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BiaViewModel(
    private val repository: BiaRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val nutritionPathTrigger: NutritionPathTrigger? = null,
    private val biaProgressCoachScheduler: BiaProgressCoachScheduler? = null,
) : ViewModel() {

    val history: StateFlow<List<BiaMeasurementEntity>> = activeProfileStore.activeProfileId
        .filter { it > 0 }
        .flatMapLatest { repository.all(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saved = MutableSharedFlow<String?>(extraBufferCapacity = 1)

    /**
     * Emits after a successful save. The value is always null now: saving never queues a paid job nor asks
     * the screen to open the goal-advice page. Kept as `String?` so existing collectors still compile.
     */
    val saved = _saved.asSharedFlow()

    private val _goalAdvice = MutableSharedFlow<NutritionPathTrigger.Advice>(extraBufferCapacity = 1)

    /**
     * Invite for a goal advice after new data ([NutritionPathTrigger.Advice.Suggest]: show a non-invasive prompt and,
     * if accepted, go through the standard AI confirmation) or notice that the explicit automation queued it
     * ([NutritionPathTrigger.Advice.Queued]). The UI (BiaActivity, T3) collects this; nothing navigates automatically.
     */
    val goalAdvice = _goalAdvice.asSharedFlow()

    private val _updated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updated = _updated.asSharedFlow()

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
        fatMassKg: Float? = null,
        leanMassKg: Float? = null,
        bodyWaterKg: Float? = null,
        subcutaneousFatPercent: Float? = null,
        boneMassKg: Float? = null,
        proteinPercent: Float? = null,
        proteinKg: Float? = null,
        bodyAgeYears: Int? = null,
        bmi: Float? = null,
        /** Provenance, e.g. "Import foto. Sorgente: Fitdays. Derivati: ..."; BiaActivity passes the preview notes (T3/K4 wires it). */
        notes: String? = null,
    ) {
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            _error.tryEmit("Nessun profilo attivo")
            return
        }
        val values = BodyValueRanges.BiaValues(
            weightKg, bodyFatPercent, visceralFatLevel, muscleMassKg, skeletalMuscleKg, bodyWaterPercent, bmrKcal,
            fatMassKg, leanMassKg, bodyWaterKg, subcutaneousFatPercent, boneMassKg, proteinPercent, proteinKg,
            bodyAgeYears?.toFloat(), bmi,
        )
        if (values.isEmpty()) {
            _error.tryEmit("Inserisci almeno un valore BIA")
            return
        }
        BodyValueRanges.validateBia(values)?.let {
            _error.tryEmit(it)
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
                        notes = notes?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTES_CHARS),
                        fatMassKg = fatMassKg,
                        leanMassKg = leanMassKg,
                        bodyWaterKg = bodyWaterKg,
                        subcutaneousFatPercent = subcutaneousFatPercent,
                        boneMassKg = boneMassKg,
                        proteinPercent = proteinPercent,
                        proteinKg = proteinKg,
                        bodyAgeYears = bodyAgeYears,
                        bmi = bmi,
                    )
                )
            }.onSuccess {
                // The BIA is already persisted. No paid AI job is queued here: the screen receives
                // [goalAdvice] (invite, or "queued" only if the user enabled the explicit automation)
                // and never navigates by itself.
                runCatching { nutritionPathTrigger?.onMeasurementsChanged(profileId) }.getOrNull()
                    ?.let { _goalAdvice.tryEmit(it) }
                runCatching { biaProgressCoachScheduler?.ensureScheduled(profileId) }
                _saved.tryEmit(null)
            }
                .onFailure { _error.tryEmit("Impossibile salvare la misurazione BIA") }
        }
    }

    /** In-place edit; never create another BIA reading or overwrite another profile. */
    fun update(
        original: BiaMeasurementEntity,
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
        fatMassKg: Float? = null,
        leanMassKg: Float? = null,
        bodyWaterKg: Float? = null,
        subcutaneousFatPercent: Float? = null,
        boneMassKg: Float? = null,
        proteinPercent: Float? = null,
        proteinKg: Float? = null,
        bodyAgeYears: Int? = null,
        bmi: Float? = null,
    ) {
        val profileId = activeProfileStore.currentIdOrNull()
        if (profileId == null || original.profileId != profileId) {
            _error.tryEmit("La rilevazione non appartiene al profilo attivo")
            return
        }
        val values = BodyValueRanges.BiaValues(
            weightKg, bodyFatPercent, visceralFatLevel, muscleMassKg, skeletalMuscleKg, bodyWaterPercent, bmrKcal,
            fatMassKg, leanMassKg, bodyWaterKg, subcutaneousFatPercent, boneMassKg, proteinPercent, proteinKg,
            bodyAgeYears?.toFloat(), bmi,
        )
        if (values.isEmpty()) {
            _error.tryEmit("Inserisci almeno un valore BIA")
            return
        }
        BodyValueRanges.validateBia(values)?.let {
            _error.tryEmit(it)
            return
        }
        viewModelScope.launch {
            runCatching {
                val saved = repository.all(profileId).first().firstOrNull { it.id == original.id }
                    ?: error("Rilevazione non più disponibile")
                repository.update(
                    saved.copy(
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
                        fatMassKg = fatMassKg,
                        leanMassKg = leanMassKg,
                        bodyWaterKg = bodyWaterKg,
                        subcutaneousFatPercent = subcutaneousFatPercent,
                        boneMassKg = boneMassKg,
                        proteinPercent = proteinPercent,
                        proteinKg = proteinKg,
                        bodyAgeYears = bodyAgeYears,
                        bmi = bmi,
                    )
                )
            }.onSuccess {
                // An edit changes the data an existing analysis used: [BiaAnalysisPolicy.isObsolete] flags it.
                // No paid job and no navigation: only the optional invite for the screen.
                runCatching { nutritionPathTrigger?.onMeasurementsChanged(profileId) }.getOrNull()
                    ?.let { _goalAdvice.tryEmit(it) }
                runCatching { biaProgressCoachScheduler?.ensureScheduled(profileId) }
                _updated.tryEmit(Unit)
            }.onFailure { _error.tryEmit("Impossibile aggiornare la misurazione BIA") }
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
        private val nutritionPathTrigger: NutritionPathTrigger? = null,
        private val biaProgressCoachScheduler: BiaProgressCoachScheduler? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BiaViewModel::class.java))
            return BiaViewModel(repository, activeProfileStore, nutritionPathTrigger, biaProgressCoachScheduler) as T
        }
    }

    private companion object {
        const val MAX_NOTES_CHARS = 2_000
    }
}
