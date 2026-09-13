package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionAdviceService
import com.myfitai.app.notifications.NotificationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NutritionAdviceViewModel(
    private val adviceService: NutritionAdviceService,
    private val cheatService: CheatAdjustmentService,
    private val notificationScheduler: NotificationScheduler,
) : ViewModel() {

    data class State(
        val running: Boolean = false,
        val accepting: Boolean = false,
        val question: String = "",
        val answer: String? = null,
        val suggestions: List<NutritionAdviceContract.Suggestion> = emptyList(),
        val assumptions: String = "",
        val providerLabel: String? = null,
        val error: String? = null,
        val acceptedResult: CheatAdjustmentService.Result? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun ask(question: String) {
        if (_state.value.running || _state.value.accepting) return
        val normalized = question.trim()
        if (normalized.isBlank()) {
            _state.value = State(error = "Scrivi una domanda alimentare.")
            return
        }
        _state.value = State(running = true, question = normalized)
        viewModelScope.launch {
            runCatching { adviceService.ask(normalized) }
                .onSuccess { result ->
                    _state.value = State(
                        question = normalized,
                        answer = result.answer,
                        suggestions = result.suggestions,
                        assumptions = result.assumptions,
                        providerLabel = result.providerLabel,
                    )
                }
                .onFailure { error ->
                    _state.value = State(question = normalized, error = error.message ?: "Consiglio non disponibile")
                }
        }
    }

    fun acceptSuggestion(suggestion: NutritionAdviceContract.Suggestion) {
        if (_state.value.running || _state.value.accepting) return
        val previous = _state.value
        _state.value = previous.copy(accepting = true, error = null)
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val input = CheatAdjustmentService.Input(
                    description = suggestion.title,
                    quantityText = "Suggerimento IA accettato",
                    notes = buildString {
                        append("Suggerito da Chiedi all'IA. ")
                        append(suggestion.reason)
                        append(" Stima proposta: ${suggestion.estimatedKcal} kcal, P ${suggestion.proteinG} g, C ${suggestion.carbsG} g, F ${suggestion.fatG} g.")
                    },
                    occurredAtEpochMillis = now,
                )
                // Reuse the authoritative cheat flow: the nutrition suggestion is re-read by the
                // deviation agent, then immediately persisted/adapted because the user explicitly accepted it.
                val understanding = cheatService.analyze(input)
                cheatService.registerAndAdapt(input, understanding)
            }.onSuccess { result ->
                runCatching { notificationScheduler.refresh() }
                _state.value = previous.copy(accepting = false, acceptedResult = result, error = null)
            }.onFailure { error ->
                _state.value = previous.copy(accepting = false, error = error.message ?: "Impossibile applicare il suggerimento")
            }
        }
    }

    fun consumeAcceptedResult() {
        if (_state.value.acceptedResult != null) _state.value = _state.value.copy(acceptedResult = null)
    }

    class Factory(
        private val adviceService: NutritionAdviceService,
        private val cheatService: CheatAdjustmentService,
        private val notificationScheduler: NotificationScheduler,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NutritionAdviceViewModel::class.java))
            return NutritionAdviceViewModel(adviceService, cheatService, notificationScheduler) as T
        }
    }
}
