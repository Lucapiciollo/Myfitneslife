package com.myfitai.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileEditViewModel(
    private val repository: UserProfileRepository,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {

    private val _profile = MutableStateFlow<UserProfileEntity?>(null)
    val profile: StateFlow<UserProfileEntity?> = _profile.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    init {
        loadActiveProfile()
    }

    fun loadActiveProfile() {
        viewModelScope.launch {
            val id = activeProfileStore.currentIdOrNull()
            _profile.value = if (id != null) repository.get(id) else null
            if (_profile.value == null) _error.tryEmit("Nessun profilo attivo")
        }
    }

    fun save(
        name: String,
        birthDateEpochDay: Long?,
        heightCm: Float?,
        currentWeightKg: Float?,
        goal: String?,
        activityLevel: String?,
        wakeTimeMinutes: Int?,
        sleepTimeMinutes: Int?,
        dietaryPreferencesJson: String?,
    ) {
        val current = _profile.value ?: run {
            _error.tryEmit("Profilo non disponibile")
            return
        }

        viewModelScope.launch {
            _saving.value = true
            runCatching {
                val updated = current.copy(
                    name = name.trim(),
                    birthDateEpochDay = birthDateEpochDay,
                    heightCm = heightCm,
                    currentWeightKg = currentWeightKg,
                    goal = goal?.trim()?.takeIf(String::isNotEmpty),
                    activityLevel = activityLevel?.trim()?.takeIf(String::isNotEmpty),
                    wakeTimeMinutes = wakeTimeMinutes,
                    sleepTimeMinutes = sleepTimeMinutes,
                    dietaryPreferencesJson = dietaryPreferencesJson,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                repository.update(updated)
                _profile.value = updated
            }.onSuccess {
                _saved.tryEmit(Unit)
            }.onFailure {
                _error.tryEmit("Impossibile salvare il profilo")
            }
            _saving.value = false
        }
    }

    class Factory(
        private val repository: UserProfileRepository,
        private val activeProfileStore: ActiveProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProfileEditViewModel::class.java))
            return ProfileEditViewModel(repository, activeProfileStore) as T
        }
    }
}
