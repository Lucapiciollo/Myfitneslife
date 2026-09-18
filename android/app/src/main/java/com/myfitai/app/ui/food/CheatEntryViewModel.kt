package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import com.myfitai.app.data.local.entity.AiJobResultEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.AiJobResultRepository
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobState
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.ai.AiImageJobStore
import com.myfitai.app.domain.ai.CheatAdjustmentAiJobHandler
import com.myfitai.app.domain.ai.CheatUnderstandingAiJobHandler
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.notifications.NotificationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CheatEntryViewModel(
    private val service: CheatAdjustmentService,
    private val notificationScheduler: NotificationScheduler,
    private val activeProfileStore: ActiveProfileStore,
    private val scheduler: AiJobScheduler,
    private val results: AiJobResultRepository,
    private val imageStore: AiImageJobStore,
) : ViewModel() {

    data class State(
        val running: Boolean = false,
        val error: String? = null,
        val understanding: CheatAdjustmentService.Understanding? = null,
        val result: CheatAdjustmentService.Result? = null,
        val previewJobKey: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun analyze(input: CheatAdjustmentService.Input) {
        if (_state.value.running) return
        val profileId = activeProfileStore.currentIdOrNull() ?: run {
            _state.value = State(error = "Profilo non disponibile")
            return
        }
        val jobKey = CheatUnderstandingAiJobHandler.jobKeyFor(input)
        _state.value = State(running = true, previewJobKey = jobKey)
        val imagePath = input.labelImage?.let(imageStore::write)
        val inputData = Data.Builder().putString(
            CheatUnderstandingAiJobHandler.KEY_INPUT_JSON,
            CheatUnderstandingAiJobHandler.encodeInput(input),
        )
        imagePath?.let { inputData.putString(CheatUnderstandingAiJobHandler.KEY_IMAGE_PATH, it) }
        scheduler.enqueue(
            AiJobType.CHEAT_UNDERSTANDING,
            profileId,
            jobKey,
            inputData.build(),
        )
        observeUnderstanding(profileId, jobKey)
    }

    fun confirm(input: CheatAdjustmentService.Input) {
        if (_state.value.running) return
        val understanding = _state.value.understanding ?: run {
            _state.value = _state.value.copy(error = "Fai prima valutare lo sgarro all'IA.")
            return
        }
        val profileId = activeProfileStore.currentIdOrNull() ?: return
        val previewJobKey = _state.value.previewJobKey ?: CheatUnderstandingAiJobHandler.jobKeyFor(input)
        _state.value = _state.value.copy(running = true, error = null)
        viewModelScope.launch {
            val row = results.find(profileId, AiJobType.CHEAT_UNDERSTANDING, previewJobKey)
            val confirmedJson = row?.payloadJson
            if (confirmedJson == null) {
                _state.value = _state.value.copy(running = false, error = "La valutazione IA non è più disponibile: rivaluta lo sgarro.")
                return@launch
            }
            val jobKey = CheatAdjustmentAiJobHandler.jobKeyFor(previewJobKey)
            scheduler.enqueue(
                AiJobType.CHEAT_ADJUSTMENT,
                profileId,
                jobKey,
                Data.Builder().putString(CheatUnderstandingAiJobHandler.KEY_CONFIRMED_JSON, confirmedJson).build(),
            )
            observeAdjustment(profileId, jobKey, understanding)
        }
    }

    fun invalidateUnderstanding() {
        if (_state.value.running) return
        if (_state.value.understanding != null || _state.value.error != null) _state.value = State()
    }

    fun consumeResult() {
        if (_state.value.result != null) _state.value = State()
    }

    /** Reattaches to a preview or adaptation opened from its notification without another AI call. */
    fun reattachToJob(previewJobKey: String) {
        val profileId = activeProfileStore.currentIdOrNull() ?: return
        viewModelScope.launch {
            val preview = results.find(profileId, AiJobType.CHEAT_UNDERSTANDING, previewJobKey)
            val understanding = preview?.payloadJson?.let { CheatUnderstandingAiJobHandler.decodePreview(it).second }
            if (understanding != null) {
                _state.value = State(understanding = understanding, previewJobKey = previewJobKey)
            }
            val adjustmentKey = CheatAdjustmentAiJobHandler.jobKeyFor(previewJobKey)
            val adjustment = results.find(profileId, AiJobType.CHEAT_ADJUSTMENT, adjustmentKey)
            if (adjustment?.status == AiJobResultEntity.STATUS_SUCCEEDED && adjustment.payloadJson != null) {
                _state.value = State(result = decodeResult(adjustment.payloadJson))
            } else if (adjustment?.status == AiJobResultEntity.STATUS_FAILED) {
                _state.value = _state.value.copy(error = adjustment.errorMessage)
            }
        }
    }

    private fun message(error: Throwable): String = when (error) {
        is CheatAdjustmentService.AdjustmentException.NeedsInput -> "Completa prima: ${error.fields.joinToString()}"
        is CheatAdjustmentService.AdjustmentException.NoPlanForWeek -> "Genera prima un piano alimentare per la settimana dello sgarro."
        is CheatAdjustmentService.AdjustmentException.PreviewStale -> error.message ?: "Rivaluta lo sgarro prima di confermare."
        else -> error.message ?: "Impossibile valutare lo sgarro"
    }

    private fun observeUnderstanding(profileId: Long, jobKey: String) {
        viewModelScope.launch {
            scheduler.observe(AiJobType.CHEAT_UNDERSTANDING, profileId, jobKey).collect { state ->
                when (state) {
                    AiJobState.Idle -> Unit
                    AiJobState.Running -> _state.value = _state.value.copy(running = true)
                    is AiJobState.Succeeded -> {
                        scheduler.consume(state.id)
                        val row = results.find(profileId, AiJobType.CHEAT_UNDERSTANDING, jobKey)
                        val understanding = row?.payloadJson?.let { CheatUnderstandingAiJobHandler.decodePreview(it).second }
                        _state.value = if (understanding == null) State(error = "Valutazione IA non disponibile") else State(understanding = understanding, previewJobKey = jobKey)
                    }
                    is AiJobState.Failed -> {
                        scheduler.consume(state.id)
                        _state.value = State(error = state.message)
                    }
                }
            }
        }
    }

    private fun observeAdjustment(profileId: Long, jobKey: String, understanding: CheatAdjustmentService.Understanding) {
        viewModelScope.launch {
            scheduler.observe(AiJobType.CHEAT_ADJUSTMENT, profileId, jobKey).collect { state ->
                when (state) {
                    AiJobState.Idle -> Unit
                    AiJobState.Running -> _state.value = _state.value.copy(running = true)
                    is AiJobState.Succeeded -> {
                        scheduler.consume(state.id)
                        val row = results.find(profileId, AiJobType.CHEAT_ADJUSTMENT, jobKey)
                        val result = row?.payloadJson?.let { decodeResult(it) }
                        runCatching { notificationScheduler.refresh() }
                        _state.value = if (result == null) State(understanding = understanding, error = "Adattamento non disponibile") else State(result = result)
                    }
                    is AiJobState.Failed -> {
                        scheduler.consume(state.id)
                        _state.value = State(understanding = understanding, error = state.message)
                    }
                }
            }
        }
    }

    private fun decodeResult(json: String): CheatAdjustmentService.Result {
        val root = org.json.JSONObject(json)
        val meals = root.optJSONArray("modifiedMeals")?.let { array -> (0 until array.length()).map { array.optString(it) } } ?: emptyList()
        return CheatAdjustmentService.Result(
            cheatId = root.optLong("cheatId"), adapted = root.optBoolean("adapted"),
            newVersionId = root.optLong("newVersionId").takeIf { root.has("newVersionId") && !root.isNull("newVersionId") },
            estimatedKcal = root.optInt("estimatedKcal").takeIf { root.has("estimatedKcal") && !root.isNull("estimatedKcal") },
            estimateSummary = root.optString("estimateSummary"), adaptationSummary = root.optString("adaptationSummary"),
            modifiedMeals = meals,
        )
    }

    class Factory(
        private val service: CheatAdjustmentService,
        private val notificationScheduler: NotificationScheduler,
        private val activeProfileStore: ActiveProfileStore,
        private val scheduler: AiJobScheduler,
        private val results: AiJobResultRepository,
        private val imageStore: AiImageJobStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CheatEntryViewModel::class.java))
            return CheatEntryViewModel(service, notificationScheduler, activeProfileStore, scheduler, results, imageStore) as T
        }
    }
}
