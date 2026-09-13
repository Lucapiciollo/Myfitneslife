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
        val result: CheatAdjustmentService.Result? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun submit(input: CheatAdjustmentService.Input) {
        if (_state.value.running) return
        _state.value = State(running = true)
        viewModelScope.launch {
            runCatching { service.registerAndAdapt(input) }
                .onSuccess {
                    runCatching { notificationScheduler.refresh() }
                    _state.value = State(result = it)
                }
                .onFailure { error ->
                    val message = when (error) {
                        is CheatAdjustmentService.AdjustmentException.NeedsInput -> "Completa prima: ${error.fields.joinToString()}"
                        else -> error.message ?: "Impossibile registrare lo sgarro"
                    }
                    _state.value = State(error = message)
                }
        }
    }

    fun consumeResult() {
        if (_state.value.result != null) _state.value = State()
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
