package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.notifications.NotificationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CheatEntryViewModel(
    private val service: CheatAdjustmentService,
    private val notificationScheduler: NotificationScheduler,
) : ViewModel() {

    data class State(
        val running: Boolean = false,
        val error: String? = null,
        val understanding: CheatAdjustmentService.Understanding? = null,
        val result: CheatAdjustmentService.Result? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun analyze(input: CheatAdjustmentService.Input) {
        if (_state.value.running) return
        _state.value = State(running = true)
        viewModelScope.launch {
            runCatching { service.analyze(input) }
                .onSuccess { _state.value = State(understanding = it) }
                .onFailure { error -> _state.value = State(error = message(error)) }
        }
    }

    fun confirm(input: CheatAdjustmentService.Input) {
        if (_state.value.running) return
        val understanding = _state.value.understanding ?: run {
            _state.value = _state.value.copy(error = "Fai prima valutare lo sgarro all'IA.")
            return
        }
        _state.value = _state.value.copy(running = true, error = null)
        viewModelScope.launch {
            runCatching { service.registerAndAdapt(input, understanding) }
                .onSuccess {
                    runCatching { notificationScheduler.refresh() }
                    _state.value = State(result = it)
                }
                .onFailure { error ->
                    _state.value = State(understanding = understanding, error = message(error))
                }
        }
    }

    fun invalidateUnderstanding() {
        if (_state.value.running) return
        if (_state.value.understanding != null || _state.value.error != null) _state.value = State()
    }

    fun consumeResult() {
        if (_state.value.result != null) _state.value = State()
    }

    private fun message(error: Throwable): String = when (error) {
        is CheatAdjustmentService.AdjustmentException.NeedsInput -> "Completa prima: ${error.fields.joinToString()}"
        is CheatAdjustmentService.AdjustmentException.PreviewStale -> error.message ?: "Rivaluta lo sgarro prima di confermare."
        else -> error.message ?: "Impossibile valutare lo sgarro"
    }

    class Factory(
        private val service: CheatAdjustmentService,
        private val notificationScheduler: NotificationScheduler,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CheatEntryViewModel::class.java))
            return CheatEntryViewModel(service, notificationScheduler) as T
        }
    }
}
