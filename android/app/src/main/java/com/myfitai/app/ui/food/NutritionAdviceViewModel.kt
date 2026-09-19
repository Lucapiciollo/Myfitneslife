package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionAdviceService
import com.myfitai.app.notifications.NotificationScheduler
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.food.NutritionAdviceAiJobHandler
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.NutritionEstimateFormatter
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NutritionAdviceViewModel(
    private val adviceService: NutritionAdviceService,
    private val cheatService: CheatAdjustmentService,
    private val notificationScheduler: NotificationScheduler,
    private val aiJobScheduler: AiJobScheduler,
    private val activeProfileStore: ActiveProfileStore,
    private val pendingJobKey: String? = null,
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

    init {
        val jobKey = pendingJobKey
        if (!jobKey.isNullOrBlank()) {
            observeJob(jobKey)
        }
    }

    fun ask(question: String) {
        if (_state.value.running || _state.value.accepting) return
        val normalized = question.trim()
        if (normalized.isBlank()) {
            _state.value = State(error = "Scrivi una domanda alimentare.")
            return
        }
        _state.value = State(running = true, question = normalized)
        viewModelScope.launch {
            val profileId = activeProfileStore.currentIdOrNull()
            if (profileId == null) {
                _state.value = State(question = normalized, error = "Seleziona prima un profilo attivo.")
            } else {
                val jobKey = jobKeyFor(normalized)
                aiJobScheduler.enqueue(AiJobType.NUTRITION_ADVICE, profileId, jobKey, params = androidx.work.Data.Builder().putString(NutritionAdviceAiJobHandler.KEY_QUESTION, normalized).build())
                observeJob(jobKey)
            }
        }
    }

    private fun observeJob(jobKey: String) {
        viewModelScope.launch {
            val profileId = activeProfileStore.currentIdOrNull() ?: return@launch
            aiJobScheduler.observe(AiJobType.NUTRITION_ADVICE, profileId, jobKey).collect { info ->
                when (info?.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> _state.value = _state.value.copy(running = true)
                    WorkInfo.State.SUCCEEDED -> renderPayload(info.outputData.getString(NutritionAdviceAiJobHandler.KEY_PAYLOAD))
                    WorkInfo.State.FAILED -> _state.value = _state.value.copy(running = false, error = info.outputData.getString("error") ?: "Consiglio non disponibile")
                    else -> Unit
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
                        append(" Stima proposta: ${NutritionEstimateFormatter.formatEstimatedKcal(suggestion.estimatedKcal)}, P ${NutritionEstimateFormatter.formatEstimatedMacro(suggestion.proteinG, "g")}, C ${NutritionEstimateFormatter.formatEstimatedMacro(suggestion.carbsG, "g")}, F ${NutritionEstimateFormatter.formatEstimatedMacro(suggestion.fatG, "g")}.")
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
        private val aiJobScheduler: AiJobScheduler,
        private val activeProfileStore: ActiveProfileStore,
        private val pendingJobKey: String? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NutritionAdviceViewModel::class.java))
            return NutritionAdviceViewModel(adviceService, cheatService, notificationScheduler, aiJobScheduler, activeProfileStore, pendingJobKey) as T
        }
    }

    private fun renderPayload(payload: String?) {
        if (payload == null) return
        runCatching {
            val root = org.json.JSONObject(payload)
            val array = root.optJSONArray("suggestions") ?: org.json.JSONArray()
            val suggestions = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val foods = item.optJSONArray("foods")?.let { values -> buildList { for (j in 0 until values.length()) add(values.optString(j)) } } ?: emptyList()
                    add(NutritionAdviceContract.Suggestion(item.optString("title"), item.optString("reason"), item.optInt("estimatedKcal"), item.optDouble("proteinG").toFloat(), item.optDouble("carbsG").toFloat(), item.optDouble("fatG").toFloat(), foods))
                }
            }
            _state.value = State(question = root.optString("question"), answer = root.optString("answer"), suggestions = suggestions, assumptions = root.optString("assumptions"), providerLabel = root.optString("providerLabel"))
        }.onFailure { _state.value = _state.value.copy(running = false, error = "Consiglio non disponibile") }
    }

    companion object {
        fun jobKeyFor(question: String) = (question.trim().hashCode() and 0x7fffffff).toString()
    }
}
