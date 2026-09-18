package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.CheatAdjustmentContract
import com.myfitai.app.notifications.NotificationScheduler
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.food.CheatUnderstandingAiJobHandler
import com.myfitai.app.domain.ai.AiImageJobStore
import com.myfitai.app.data.profile.ActiveProfileStore
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CheatEntryViewModel(
    private val service: CheatAdjustmentService,
    private val notificationScheduler: NotificationScheduler,
    private val aiJobScheduler: AiJobScheduler,
    private val activeProfileStore: ActiveProfileStore,
    private val imageStore: AiImageJobStore,
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
        val profileId = activeProfileStore.currentIdOrNull() ?: run { _state.value = State(error = "Nessun profilo attivo"); return }
        val jobKey = input.occurredAtEpochMillis.toString()
        val params = androidx.work.Data.Builder().putString(CheatUnderstandingAiJobHandler.KEY_DESCRIPTION, input.description).putString(CheatUnderstandingAiJobHandler.KEY_QUANTITY, input.quantityText).putString(CheatUnderstandingAiJobHandler.KEY_NOTES, input.notes).putLong(CheatUnderstandingAiJobHandler.KEY_OCCURRED_AT, input.occurredAtEpochMillis)
        input.labelImage?.let { params.putString(com.myfitai.app.domain.ai.AiJobWorker.KEY_IMAGE_PATH, imageStore.write(it)) }
        aiJobScheduler.enqueue(AiJobType.CHEAT_UNDERSTANDING, profileId, jobKey, params = params.build())
        viewModelScope.launch {
            aiJobScheduler.observe(AiJobType.CHEAT_UNDERSTANDING, profileId, jobKey).collect { info ->
                when (info?.state) {
                    WorkInfo.State.SUCCEEDED -> renderUnderstanding(info.outputData.getString(CheatUnderstandingAiJobHandler.KEY_PAYLOAD))
                    WorkInfo.State.FAILED -> _state.value = State(error = info.outputData.getString("error") ?: "Valutazione sgarro non disponibile")
                    else -> Unit
                }
            }
        }
    }

    private fun renderUnderstanding(payload: String?) {
        if (payload == null) return
        runCatching {
            val root = org.json.JSONObject(payload)
            _state.value = State(understanding = CheatAdjustmentService.Understanding(root.getString("understoodFood"), CheatAdjustmentContract.Estimate(root.getInt("kcal"), root.getDouble("proteinG").toFloat(), root.getDouble("carbsG").toFloat(), root.getDouble("fatG").toFloat(), root.getString("confidence"), root.getString("notes")), root.getString("provider"), root.getString("model"), ""))
        }.onFailure { _state.value = State(error = "Valutazione sgarro non disponibile") }
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
        private val aiJobScheduler: AiJobScheduler,
        private val activeProfileStore: ActiveProfileStore,
        private val imageStore: AiImageJobStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CheatEntryViewModel::class.java))
            return CheatEntryViewModel(service, notificationScheduler, aiJobScheduler, activeProfileStore, imageStore) as T
        }
    }
}
