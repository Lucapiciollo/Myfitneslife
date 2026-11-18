package com.myfitai.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.HealthDeclarationSnapshot
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.domain.profile.ProfileAgeRule
import com.myfitai.app.domain.safety.HealthDeclaration
import com.myfitai.app.domain.safety.HealthDeclarationDecodeResult
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ProfileEditViewModel(
    private val repository: UserProfileRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val allowCreate: Boolean = false,
    /** Testo mostrato quando l'età non è valida; fornito dalla UI per restare localizzato. */
    private val ageMessage: (ProfileAgeRule.Status) -> String,
    private val time: TimeProvider = SystemTimeProvider,
) : ViewModel() {

    data class HealthDeclarationDraft(
        val pregnancyOrLactation: Boolean?,
        val clinicianManagedConditionOrMedication: Boolean?,
    ) {
        override fun toString(): String = "HealthDeclarationDraft(<redacted>)"
    }

    /** Retained only by this ViewModel: never placed in saved state, preferences, intents, or Room until save. */
    data class HealthDeclarationEditorState(
        val profileId: Long? = null,
        val baseline: HealthDeclarationSnapshot? = null,
        val draft: HealthDeclarationDraft = HealthDeclarationDraft(null, null),
        val dirty: Boolean = false,
        val stale: Boolean = false,
    ) {
        override fun toString(): String =
            "HealthDeclarationEditorState(profileId=$profileId, baseline=<redacted>, draft=<redacted>, dirty=<redacted>, stale=<redacted>)"
    }

    enum class HealthDeclarationSaveStatus { IDLE, SAVING, SAVED, STALE, CONFLICT, FAILED }

    data class HealthDeclarationSaveState(
        val profileId: Long? = null,
        val status: HealthDeclarationSaveStatus = HealthDeclarationSaveStatus.IDLE,
    ) {
        override fun toString(): String = "HealthDeclarationSaveState(profileId=$profileId, status=$status)"
    }

    private val _healthDeclarationEditor = MutableStateFlow(HealthDeclarationEditorState())
    val healthDeclarationEditor: StateFlow<HealthDeclarationEditorState> = _healthDeclarationEditor.asStateFlow()
    private val _healthDeclarationSaveState = MutableStateFlow(HealthDeclarationSaveState())
    val healthDeclarationSaveState: StateFlow<HealthDeclarationSaveState> = _healthDeclarationSaveState.asStateFlow()

    private val _profile = MutableStateFlow<UserProfileEntity?>(null)
    val profile: StateFlow<UserProfileEntity?> = _profile.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    data class SavedEvent(val profileId: Long, val nutritionDataChanged: Boolean)

    private val _saved = MutableSharedFlow<SavedEvent>(extraBufferCapacity = 1)
    val saved: SharedFlow<SavedEvent> = _saved.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    init { loadActiveProfile() }

    /** Reads only the active profile; a retained dirty draft never adopts a newer baseline. */
    suspend fun readHealthDeclaration(profileId: Long, refresh: Boolean = false): HealthDeclarationDecodeResult? {
        if (allowCreate || profileId <= 0) return null
        if (activeProfileStore.currentIdOrNull() != profileId) {
            markHealthDeclarationStale(profileId)
            return null
        }
        if (_healthDeclarationEditor.value.profileId != profileId) {
            _healthDeclarationEditor.value = HealthDeclarationEditorState(profileId = profileId)
        }
        val current = _healthDeclarationEditor.value
        if (current.stale) return current.baseline?.decoded
        if (!refresh && current.baseline != null) return current.baseline.decoded

        val latest = repository.healthDeclarationSnapshot(profileId)
        val afterRead = _healthDeclarationEditor.value
        if (afterRead.profileId != profileId) return null
        if (activeProfileStore.currentIdOrNull() != profileId) {
            markHealthDeclarationStale(profileId)
            return null
        }
        if (afterRead.stale) return afterRead.baseline?.decoded
        if (latest == null) {
            if (afterRead.baseline != null) markHealthDeclarationStale(profileId)
            return null
        }
        if (afterRead.baseline != null && afterRead.dirty) {
            if (!afterRead.baseline.hasSameRawBaseline(latest)) {
                // Keep the original CAS token. A refresh must not re-enable a draft based on an older value.
                markHealthDeclarationStale(profileId)
            }
            return afterRead.baseline.decoded
        }

        _healthDeclarationEditor.value = afterRead.copy(
            baseline = latest,
            draft = latest.decoded.toHealthDeclarationDraft(),
            dirty = false,
        )
        return latest.decoded
    }

    fun updateHealthDeclarationDraft(profileId: Long, draft: HealthDeclarationDraft) {
        val current = _healthDeclarationEditor.value
        val baseline = current.baseline ?: return
        if (current.profileId != profileId) return
        if (activeProfileStore.currentIdOrNull() != profileId) {
            markHealthDeclarationStale(profileId)
            return
        }
        if (current.stale) return
        _healthDeclarationEditor.value = current.copy(
            draft = draft,
            dirty = draft.isDirtyAgainst(baseline.decoded),
        )
        if (_healthDeclarationSaveState.value.let {
                it.profileId == profileId && it.status == HealthDeclarationSaveStatus.SAVED
            }
        ) {
            _healthDeclarationSaveState.value = HealthDeclarationSaveState(profileId)
        }
    }

    /** A profile switch invalidates the retained editing session until the Activity/ViewModel is reopened. */
    fun markHealthDeclarationStale(profileId: Long) {
        val current = _healthDeclarationEditor.value
        if (current.profileId == profileId && current.baseline != null && !current.stale) {
            _healthDeclarationEditor.value = current.copy(stale = true)
        }
    }

    /** Clears a stale editing session only after the UI explicitly confirms abandonment. */
    fun abandonHealthDeclarationDraft(profileId: Long) {
        val current = _healthDeclarationEditor.value
        if (!allowCreate && current.profileId == profileId) {
            _healthDeclarationEditor.value = HealthDeclarationEditorState(profileId = profileId)
            if (_healthDeclarationSaveState.value.profileId == profileId) {
                _healthDeclarationSaveState.value = HealthDeclarationSaveState(profileId)
            }
        }
    }

    /** Atomically writes against the exact raw JSON captured by this retained editor session. */
    suspend fun saveHealthDeclaration(profileId: Long, declaration: HealthDeclaration): Boolean =
        viewModelScope.async { saveHealthDeclarationInViewModelScope(profileId, declaration) }.await()

    private suspend fun saveHealthDeclarationInViewModelScope(
        profileId: Long,
        declaration: HealthDeclaration,
    ): Boolean {
        if (allowCreate || profileId <= 0 || activeProfileStore.currentIdOrNull() != profileId) {
            markHealthDeclarationStale(profileId)
            _healthDeclarationSaveState.value = HealthDeclarationSaveState(profileId, HealthDeclarationSaveStatus.STALE)
            return false
        }
        val beforeWrite = _healthDeclarationEditor.value
        val baseline = beforeWrite.baseline
        if (beforeWrite.profileId != profileId || baseline == null || !beforeWrite.dirty || beforeWrite.stale) return false

        if (_healthDeclarationSaveState.value.let { it.profileId == profileId && it.status == HealthDeclarationSaveStatus.SAVING }) {
            return false
        }
        _healthDeclarationSaveState.value = HealthDeclarationSaveState(profileId, HealthDeclarationSaveStatus.SAVING)
        val saved = try {
            repository.compareAndSetHealthDeclaration(baseline, declaration)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _healthDeclarationSaveState.value = HealthDeclarationSaveState(profileId, HealthDeclarationSaveStatus.FAILED)
            return false
        }
        if (saved == null) {
            markHealthDeclarationStale(profileId)
            _healthDeclarationSaveState.value = HealthDeclarationSaveState(profileId, HealthDeclarationSaveStatus.CONFLICT)
            return false
        }

        val afterWrite = _healthDeclarationEditor.value
        if (afterWrite.profileId == profileId) {
            val savedDraft = declaration.toHealthDeclarationDraft()
            val nextDraft = if (afterWrite.draft == beforeWrite.draft) savedDraft else afterWrite.draft
            _healthDeclarationEditor.value = afterWrite.copy(
                baseline = saved,
                draft = nextDraft,
                dirty = nextDraft.isDirtyAgainst(saved.decoded),
                stale = afterWrite.stale || activeProfileStore.currentIdOrNull() != profileId,
            )
        }
        // A committed CAS is a save even if the active profile changes before this continuation runs.
        _healthDeclarationSaveState.value = HealthDeclarationSaveState(profileId, HealthDeclarationSaveStatus.SAVED)
        return true
    }

    private fun HealthDeclarationDecodeResult.toHealthDeclarationDraft(): HealthDeclarationDraft = when (this) {
        HealthDeclarationDecodeResult.Absent -> HealthDeclarationDraft(null, null)
        is HealthDeclarationDecodeResult.Incomplete -> declaration.toHealthDeclarationDraft()
        is HealthDeclarationDecodeResult.Valid -> declaration.toHealthDeclarationDraft()
        is HealthDeclarationDecodeResult.Invalid,
        is HealthDeclarationDecodeResult.UnsupportedVersion -> HealthDeclarationDraft(null, null)
    }

    private fun HealthDeclaration.toHealthDeclarationDraft() = HealthDeclarationDraft(
        pregnancyOrLactation = pregnancyOrLactation,
        clinicianManagedConditionOrMedication = clinicianManagedConditionOrMedication,
    )

    private fun HealthDeclarationDraft.isDirtyAgainst(baseline: HealthDeclarationDecodeResult): Boolean = when (baseline) {
        HealthDeclarationDecodeResult.Absent -> this != HealthDeclarationDraft(null, null)
        is HealthDeclarationDecodeResult.Incomplete -> this != baseline.declaration.toHealthDeclarationDraft()
        is HealthDeclarationDecodeResult.Valid -> this != baseline.declaration.toHealthDeclarationDraft()
        is HealthDeclarationDecodeResult.Invalid,
        is HealthDeclarationDecodeResult.UnsupportedVersion ->
            pregnancyOrLactation != null || clinicianManagedConditionOrMedication != null
    }

    fun loadActiveProfile() {
        viewModelScope.launch {
            if (allowCreate) {
                _profile.value = null
                return@launch
            }
            val id = activeProfileStore.currentIdOrNull()
            _profile.value = if (id != null) repository.get(id) else null
            if (_profile.value == null) _error.tryEmit("Nessun profilo attivo")
        }
    }

    fun save(
        name: String,
        birthDateEpochDay: Long?,
        biologicalSex: String?,
        heightCm: Float?,
        currentWeightKg: Float?,
        goal: String?,
        activityLevel: String?,
        wakeTimeMinutes: Int?,
        sleepTimeMinutes: Int?,
        dietaryPreferencesJson: String?,
    ) {
        // Difesa di dominio: nessun salvataggio con data di nascita presente ma età fuori 14-120 (o futura).
        val ageStatus = ProfileAgeRule.check(birthDateEpochDay, time.today())
        if (ProfileAgeRule.isPresentButInvalid(birthDateEpochDay, time.today())) {
            _error.tryEmit(ageMessage(ageStatus))
            return
        }
        viewModelScope.launch {
            _saving.value = true
            runCatching {
                val now = System.currentTimeMillis()
                val current = _profile.value
                if (current == null) {
                    check(allowCreate) { "Profilo non disponibile" }
                    val id = repository.create(
                        UserProfileEntity(
                            name = name.trim(),
                            birthDateEpochDay = birthDateEpochDay,
                            heightCm = heightCm,
                            currentWeightKg = currentWeightKg,
                            goal = goal?.trim()?.takeIf(String::isNotEmpty),
                            activityLevel = activityLevel?.trim()?.takeIf(String::isNotEmpty),
                            wakeTimeMinutes = wakeTimeMinutes,
                            sleepTimeMinutes = sleepTimeMinutes,
                            dietaryPreferencesJson = dietaryPreferencesJson,
                            photoPath = null,
                            createdAtEpochMillis = now,
                            updatedAtEpochMillis = now,
                            biologicalSex = biologicalSex,
                            initialWeightKg = currentWeightKg,
                        )
                    )
                    activeProfileStore.selectProfile(id, makeDefault = true)
                    _profile.value = repository.get(id)
                    SavedEvent(id, false)
                } else {
                    val normalizedGoal = goal?.trim()?.takeIf(String::isNotEmpty)
                    val normalizedActivity = activityLevel?.trim()?.takeIf(String::isNotEmpty)
                    val nutritionDataChanged = current.goal != normalizedGoal ||
                        current.activityLevel != normalizedActivity ||
                        current.birthDateEpochDay != birthDateEpochDay ||
                        current.biologicalSex != biologicalSex ||
                        current.heightCm != heightCm ||
                        current.currentWeightKg != currentWeightKg
                    val updated = current.copy(
                        name = name.trim(),
                        birthDateEpochDay = birthDateEpochDay,
                        biologicalSex = biologicalSex,
                        heightCm = heightCm,
                        initialWeightKg = current.initialWeightKg ?: currentWeightKg,
                        currentWeightKg = currentWeightKg,
                        goal = normalizedGoal,
                        activityLevel = normalizedActivity,
                        wakeTimeMinutes = wakeTimeMinutes,
                        sleepTimeMinutes = sleepTimeMinutes,
                        dietaryPreferencesJson = dietaryPreferencesJson,
                        updatedAtEpochMillis = now,
                    )
                    repository.update(updated)
                    _profile.value = updated
                    SavedEvent(updated.id, nutritionDataChanged)
                }
            }.onSuccess { event ->
                _saved.tryEmit(event)
            }.onFailure {
                _error.tryEmit("Impossibile salvare il profilo")
            }
            _saving.value = false
        }
    }

    class Factory(
        private val repository: UserProfileRepository,
        private val activeProfileStore: ActiveProfileStore,
        private val allowCreate: Boolean = false,
        private val ageMessage: (ProfileAgeRule.Status) -> String,
        private val time: TimeProvider = SystemTimeProvider,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProfileEditViewModel::class.java))
            return ProfileEditViewModel(repository, activeProfileStore, allowCreate, ageMessage, time) as T
        }
    }
}
