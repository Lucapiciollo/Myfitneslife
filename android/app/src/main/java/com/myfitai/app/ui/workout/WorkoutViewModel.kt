package com.myfitai.app.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.WorkoutRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WorkoutViewModel(
    private val repository: WorkoutRepository,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {

    val workouts: StateFlow<List<WorkoutEntity>> = activeProfileStore.activeProfileId
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
        startedAtEpochMillis: Long,
        type: String,
        title: String,
        durationMinutes: Int?,
        isRestDay: Boolean,
        notes: String?,
    ) {
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            _error.tryEmit("Nessun profilo attivo")
            return
        }
        val cleanTitle = title.trim()
        if (!isRestDay && cleanTitle.isBlank()) {
            _error.tryEmit("Inserisci il nome dell'allenamento")
            return
        }
        if (durationMinutes != null && durationMinutes !in 1..1440) {
            _error.tryEmit("Durata non valida")
            return
        }

        viewModelScope.launch {
            runCatching {
                repository.insert(
                    WorkoutEntity(
                        profileId = profileId,
                        startedAtEpochMillis = startedAtEpochMillis,
                        type = if (isRestDay) "REST" else type.trim().ifBlank { "OTHER" },
                        title = if (isRestDay) "Riposo" else cleanTitle,
                        durationMinutes = if (isRestDay) null else durationMinutes,
                        isRestDay = isRestDay,
                        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                    )
                )
            }.onSuccess { _saved.tryEmit(Unit) }
                .onFailure { _error.tryEmit("Impossibile salvare l'allenamento") }
        }
    }

    fun delete(workout: WorkoutEntity) {
        viewModelScope.launch {
            runCatching { repository.delete(workout) }
                .onSuccess { _deleted.tryEmit(Unit) }
                .onFailure { _error.tryEmit("Impossibile eliminare l'allenamento") }
        }
    }

    class Factory(
        private val repository: WorkoutRepository,
        private val activeProfileStore: ActiveProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WorkoutViewModel::class.java))
            return WorkoutViewModel(repository, activeProfileStore) as T
        }
    }
}
