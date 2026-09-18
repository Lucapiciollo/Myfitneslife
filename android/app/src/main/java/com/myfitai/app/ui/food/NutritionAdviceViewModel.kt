package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.AiJobResultEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.AiJobResultRepository
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobState
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.ai.NutritionAdviceAiJobHandler
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionAdviceService
import com.myfitai.app.notifications.NotificationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class NutritionAdviceViewModel(
    private val adviceService: NutritionAdviceService,
    private val cheatService: CheatAdjustmentService,
    private val notificationScheduler: NotificationScheduler,
    private val activeProfileStore: ActiveProfileStore,
    private val scheduler: AiJobScheduler,
    private val results: AiJobResultRepository,
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
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            _state.value = State(question = normalized, error = "Profilo non disponibile")
            return
        }
        _state.value = State(running = true, question = normalized)
        scheduler.enqueue(
            type = AiJobType.NUTRITION_ADVICE,
            profileId = profileId,
            jobKey = NutritionAdviceAiJobHandler.jobKeyFor(normalized),
            params = androidx.work.Data.Builder()
                .putString(NutritionAdviceAiJobHandler.KEY_QUESTION, normalized)
                .build(),
        )
        observeJob(profileId, normalized)
    }

    private fun observeJob(profileId: Long, question: String) {
        val jobKey = NutritionAdviceAiJobHandler.jobKeyFor(question)
        viewModelScope.launch {
            scheduler.observe(AiJobType.NUTRITION_ADVICE, profileId, jobKey).collect { jobState ->
                when (jobState) {
                    AiJobState.Idle -> Unit
                    AiJobState.Running -> _state.value = _state.value.copy(running = true, error = null)
                    is AiJobState.Succeeded -> {
                        scheduler.consume(jobState.id)
                        val result = results.find(profileId, AiJobType.NUTRITION_ADVICE, jobKey)
                        val decoded = result?.payloadJson?.let { NutritionAdviceAiJobHandler.decode(it) }
                        if (decoded == null) {
                            _state.value = _state.value.copy(running = false, error = "Risposta IA non disponibile")
                        } else {
                            _state.value = _state.value.copy(
                                running = false,
                                answer = decoded.answer,
                                suggestions = decoded.suggestions,
                                assumptions = decoded.assumptions,
                                providerLabel = decoded.providerLabel,
                                error = null,
                            )
                        }
                    }
                    is AiJobState.Failed -> {
                        scheduler.consume(jobState.id)
                        _state.value = _state.value.copy(running = false, error = jobState.message)
                    }
                }
            }
        }
    }

    /** Reattaches to a result opened from the notification without sending another provider call. */
    fun reattachToJob(jobKey: String) {
        val profileId = activeProfileStore.currentIdOrNull() ?: return
        viewModelScope.launch {
            val result = results.find(profileId, AiJobType.NUTRITION_ADVICE, jobKey)
            when {
                result?.status == AiJobResultEntity.STATUS_SUCCEEDED && result.payloadJson != null -> {
                    val decoded = NutritionAdviceAiJobHandler.decode(result.payloadJson)
                    _state.value = State(
                        question = NutritionAdviceAiJobHandler.decodeQuestion(result.payloadJson),
                        answer = decoded.answer,
                        suggestions = decoded.suggestions,
                        assumptions = decoded.assumptions,
                        providerLabel = decoded.providerLabel,
                    )
                    results.markConsumed(profileId, AiJobType.NUTRITION_ADVICE, jobKey)
                }
                result?.status == AiJobResultEntity.STATUS_FAILED ->
                    _state.value = State(error = result.errorMessage ?: "Consiglio non disponibile")
                else -> {
                    // A notification can arrive before Room observers catch up; observe by using
                    // the canonical job key and let the normal terminal-state path render it.
                    _state.value = _state.value.copy(running = true)
                    viewModelScope.launch {
                        scheduler.observe(AiJobType.NUTRITION_ADVICE, profileId, jobKey).collect { state ->
                            if (state is AiJobState.Succeeded || state is AiJobState.Failed) {
                                reattachToJob(jobKey)
                            }
                        }
                    }
                }
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
        private val activeProfileStore: ActiveProfileStore,
        private val scheduler: AiJobScheduler,
        private val results: AiJobResultRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NutritionAdviceViewModel::class.java))
            return NutritionAdviceViewModel(adviceService, cheatService, notificationScheduler, activeProfileStore, scheduler, results) as T
        }
    }
}
