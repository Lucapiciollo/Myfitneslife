package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.Operation
import com.myfitai.app.R
import com.myfitai.app.ai.AiFailureMessages
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NutritionAdviceViewModel(
    private val adviceService: NutritionAdviceService,
    private val cheatService: CheatAdjustmentService,
    private val notificationScheduler: NotificationScheduler,
    private val aiJobScheduler: AiJobScheduler,
    private val activeProfileStore: ActiveProfileStore,
    private val pendingJobKey: String? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val refreshNotifications: suspend () -> Unit = { notificationScheduler.refresh() },
    private val enqueueAdviceJob: (Long, String, Data) -> Operation = { profileId, jobKey, params ->
        aiJobScheduler.enqueue(
            AiJobType.NUTRITION_ADVICE,
            profileId,
            jobKey,
            params = params,
            replaceExisting = true,
        )
    },
    private val observeAdviceJob: (Long, String) -> Flow<WorkInfo?> = { profileId, jobKey ->
        aiJobScheduler.observe(AiJobType.NUTRITION_ADVICE, profileId, jobKey)
    },
    private val observationStartTimeoutMillis: Long = OBSERVATION_START_TIMEOUT_MILLIS,
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
        /** Why the request failed, in the closed categories of [AiFailureMessages]; null when it did not fail. */
        val failure: AiFailureMessages.Presentation? = null,
        val acceptedResult: CheatAdjustmentService.Result? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    /** Profile that owns the advice request; acceptance must not follow a later active-profile switch. */
    private var adviceProfileId: Long? = null
    /** A run identity is independent from question/prompt content and remains in memory with this ViewModel. */
    private var adviceJobKey: String? = pendingJobKey
    private var adviceRequestIdentity: String? = pendingJobKey
    internal val currentRequestRunIdentity: String? get() = adviceRequestIdentity
    internal val currentJobKey: String? get() = adviceJobKey
    /** Retained with this ViewModel across Activity/configuration recreation; no suggestion text is persisted. */
    private val acceptedOperationKeys = mutableSetOf<String>()
    private val inFlightOperationKeys = mutableSetOf<String>()
    private val operationOccurredAt = mutableMapOf<String, Long>()
    private var latestOperationOccurredAtEpochMillis: Long? = null

    init {
        val jobKey = pendingJobKey
        if (!jobKey.isNullOrBlank()) {
            _state.value = State(running = true)
            observeJob(jobKey)
        }
    }

    fun ask(question: String) {
        if (_state.value.running || _state.value.accepting) return
        val previous = _state.value
        val normalized = question.trim()
        if (normalized.isBlank()) {
            _state.value = State(error = "Scrivi una domanda alimentare.")
            return
        }
        val retryingSameRequest = previous.question == normalized && previous.failure?.canRetry == true
        val requestRunIdentity = if (retryingSameRequest) {
            adviceRequestIdentity ?: newRequestIdentity()
        } else {
            newRequestIdentity()
        }
        // The UUID is both the acceptance run identity and the opaque WorkManager key. The scheduler
        // scopes its unique name by profile, so distinct asks never replace one another.
        val jobKey = requestRunIdentity
        adviceRequestIdentity = requestRunIdentity
        adviceJobKey = jobKey
        _state.value = State(running = true, question = normalized)
        viewModelScope.launch {
            try {
                val profileId = activeProfileStore.currentIdOrNull()
                if (profileId == null) {
                    _state.value = State(question = normalized, error = "Seleziona prima un profilo attivo.")
                } else {
                    adviceProfileId = profileId
                    val params = Data.Builder()
                        .putString(NutritionAdviceAiJobHandler.KEY_QUESTION, normalized)
                        .build()
                    // Replacement is safe because this opaque job key is unique to this run (and reused only on retry).
                    enqueueAdviceJob(profileId, jobKey, params).awaitResult()
                    observeJob(jobKey, profileId, requestRunIdentity)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportStartFailure(normalized, requestRunIdentity)
            }
        }
    }

    private fun observeJob(
        jobKey: String,
        requestedProfileId: Long? = null,
        requestRunIdentity: String? = adviceRequestIdentity,
    ) {
        viewModelScope.launch {
            try {
                val profileId = requestedProfileId ?: activeProfileStore.currentIdOrNull()
                if (profileId == null) {
                    reportStartFailure(_state.value.question, requestRunIdentity)
                    return@launch
                }
                adviceProfileId = profileId
                val question = _state.value.question
                val infos = observeAdviceJob(profileId, jobKey).produceIn(this)
                try {
                    val firstPersistedInfo = infos.awaitPersistedWorkInfo(observationStartTimeoutMillis)
                    if (firstPersistedInfo == null) {
                        reportStartFailure(question, requestRunIdentity)
                        return@launch
                    }
                    var terminalStateObserved = firstPersistedInfo.state.isTerminal()
                    renderWorkInfo(firstPersistedInfo, profileId, jobKey, requestRunIdentity)
                    for (info in infos) {
                        val persistedInfo = info ?: infos.awaitPersistedWorkInfo(observationStartTimeoutMillis)
                        if (persistedInfo == null) {
                            reportStartFailure(question, requestRunIdentity)
                            return@launch
                        }
                        if (terminalStateObserved) continue
                        terminalStateObserved = persistedInfo.state.isTerminal()
                        renderWorkInfo(persistedInfo, profileId, jobKey, requestRunIdentity)
                    }
                    if (!terminalStateObserved) reportObserverCompletionFailure(question, requestRunIdentity)
                } finally {
                    infos.cancel()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportObserverCompletionFailure(_state.value.question, requestRunIdentity)
            }
        }
    }

    private fun renderWorkInfo(info: WorkInfo, profileId: Long, jobKey: String, requestRunIdentity: String?) {
        if (requestRunIdentity != adviceRequestIdentity) return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> _state.value = _state.value.copy(running = true)
            WorkInfo.State.SUCCEEDED -> renderPayload(
                info.outputData.getString(NutritionAdviceAiJobHandler.KEY_PAYLOAD),
                requestedProfileId = profileId,
                requestedJobKey = jobKey,
                requestRunIdentity = requestRunIdentity,
            )
            WorkInfo.State.FAILED -> _state.value = _state.value.copy(
                running = false,
                error = null,
                failure = presentationFor(info.outputData.getString("error")),
            )
            WorkInfo.State.CANCELLED -> reportStartFailure(_state.value.question, requestRunIdentity)
            else -> Unit
        }
    }

    private suspend fun Operation.awaitResult() {
        val result = result
        suspendCancellableCoroutine<Unit> { continuation ->
            result.addListener({
                try {
                    result.get()
                    continuation.resume(Unit)
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    continuation.cancel(cancelled)
                } catch (failed: ExecutionException) {
                    continuation.resumeWithException(failed.cause ?: failed)
                } catch (failed: Exception) {
                    continuation.resumeWithException(failed)
                }
            }, Executor { command -> command.run() })
        }
    }

    private suspend fun ReceiveChannel<WorkInfo?>.awaitPersistedWorkInfo(timeoutMillis: Long): WorkInfo? =
        withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) {
            var info = receive()
            while (info == null) info = receive()
            info
        }

    /** Never surface scheduler exceptions: they may contain the question or other private request data. */
    private fun reportStartFailure(question: String, requestRunIdentity: String?) {
        if (requestRunIdentity != adviceRequestIdentity || !_state.value.running) return
        _state.value = State(question = question, failure = AiFailureMessages.couldNotStart())
    }

    /** A normally completed observer without a terminal WorkInfo is an unavailable result, not a running request. */
    private fun reportObserverCompletionFailure(question: String, requestRunIdentity: String?) {
        if (requestRunIdentity != adviceRequestIdentity || !_state.value.running) return
        _state.value = State(
            question = question,
            failure = AiFailureMessages.resolve(null).copy(canRetry = true),
        )
    }

    private fun WorkInfo.State.isTerminal(): Boolean = when (this) {
        WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> true
        else -> false
    }

    private fun reportUnavailablePayload(requestRunIdentity: String?) {
        if ((requestRunIdentity != null && requestRunIdentity != adviceRequestIdentity) || !_state.value.running) return
        _state.value = State(
            question = _state.value.question,
            failure = AiFailureMessages.resolve(null).copy(canRetry = true),
        )
    }

    fun acceptSuggestion(suggestion: NutritionAdviceContract.Suggestion) {
        if (_state.value.running || _state.value.accepting) return
        val previous = _state.value
        val profileId = adviceProfileId
        val requestIdentity = adviceRequestIdentity
        if (profileId == null || requestIdentity.isNullOrBlank()) {
            _state.value = previous.copy(error = "Il profilo o la richiesta del suggerimento non è più disponibile.")
            return
        }
        val operationKey = acceptanceKey(profileId, requestIdentity, suggestion)
        if (operationKey in acceptedOperationKeys || !inFlightOperationKeys.add(operationKey)) return
        val occurredAtEpochMillis = operationOccurredAt.getOrPut(operationKey) {
            val now = nowEpochMillis()
            val previousOccurrence = latestOperationOccurredAtEpochMillis
            val uniqueOccurrence = if (previousOccurrence == null || now > previousOccurrence) now else previousOccurrence + 1L
            latestOperationOccurredAtEpochMillis = uniqueOccurrence
            uniqueOccurrence
        }
        _state.value = previous.copy(accepting = true, error = null)
        viewModelScope.launch {
            runCatching {
                val input = CheatAdjustmentService.Input(
                    description = suggestion.title,
                    quantityText = "Suggerimento IA accettato",
                    notes = buildString {
                        append("Suggerito da Chiedi all'IA. ")
                        append(suggestion.reason)
                        append(" Stima proposta: ${NutritionEstimateFormatter.formatEstimatedKcal(suggestion.estimatedKcal)}, P ${NutritionEstimateFormatter.formatEstimatedMacro(suggestion.proteinG, "g")}, C ${NutritionEstimateFormatter.formatEstimatedMacro(suggestion.carbsG, "g")}, F ${NutritionEstimateFormatter.formatEstimatedMacro(suggestion.fatG, "g")}.")
                    },
                    occurredAtEpochMillis = occurredAtEpochMillis,
                )
                cheatService.registerAcceptedSuggestion(profileId, input, suggestion)
            }.onSuccess { result ->
                inFlightOperationKeys.remove(operationKey)
                acceptedOperationKeys += operationKey
                runCatching { refreshNotifications() }
                _state.value = previous.copy(accepting = false, acceptedResult = result, error = null)
            }.onFailure { error ->
                inFlightOperationKeys.remove(operationKey)
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

    internal fun renderPayload(
        payload: String?,
        requestedProfileId: Long? = null,
        requestedJobKey: String? = null,
        requestRunIdentity: String? = null,
    ) {
        if (requestRunIdentity != null && requestRunIdentity != adviceRequestIdentity) return
        if (payload.isNullOrBlank()) {
            reportUnavailablePayload(requestRunIdentity)
            return
        }
        try {
            val root = org.json.JSONObject(payload)
            val question = root.getString("question")
            val answer = root.getString("answer")
            val assumptions = root.optString("assumptions")
            val providerLabel = root.optString("providerLabel")
            val array = root.getJSONArray("suggestions")
            require(question.isNotBlank() && answer.isNotBlank())
            val suggestions = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val title = item.getString("title")
                    val reason = item.getString("reason")
                    val estimatedKcal = item.getInt("estimatedKcal")
                    val proteinG = item.getDouble("proteinG").toFloat()
                    val carbsG = item.getDouble("carbsG").toFloat()
                    val fatG = item.getDouble("fatG").toFloat()
                    require(title.isNotBlank() && reason.isNotBlank() && estimatedKcal > 0)
                    require(listOf(proteinG, carbsG, fatG).all { it.isFinite() && it >= 0f })
                    val foods = item.optJSONArray("foods")?.let { values ->
                        buildList { for (j in 0 until values.length()) add(values.getString(j)) }
                    } ?: emptyList()
                    add(
                        NutritionAdviceContract.Suggestion(
                            title,
                            reason,
                            estimatedKcal,
                            proteinG,
                            carbsG,
                            fatG,
                            foods,
                        ),
                    )
                }
            }
            adviceProfileId = requestedProfileId ?: adviceProfileId ?: activeProfileStore.currentIdOrNull()
            val responseJobKey = requestedJobKey ?: pendingJobKey
            if (requestRunIdentity == null && responseJobKey != null && responseJobKey != adviceJobKey) {
                adviceJobKey = responseJobKey
                adviceRequestIdentity = newRequestIdentity()
            } else if (adviceRequestIdentity.isNullOrBlank()) {
                adviceJobKey = responseJobKey ?: adviceJobKey
                adviceRequestIdentity = newRequestIdentity()
            }
            _state.value = State(question = question, answer = answer, suggestions = suggestions, assumptions = assumptions, providerLabel = providerLabel)
        } catch (_: Exception) {
            reportUnavailablePayload(requestRunIdentity)
        }
    }

    companion object {
        private const val OBSERVATION_START_TIMEOUT_MILLIS = 10_000L

        /** Legacy content-derived key retained for callers that still open an existing pending job route. */
        fun jobKeyFor(question: String) = (question.trim().hashCode() and 0x7fffffff).toString()

        private fun newRequestIdentity(): String = UUID.randomUUID().toString()

        private fun acceptanceKey(
            profileId: Long,
            requestIdentity: String,
            suggestion: NutritionAdviceContract.Suggestion,
        ): String = fingerprint(
            "accepted-suggestion",
            profileId.toString(),
            requestIdentity,
            suggestion.title,
            suggestion.reason,
            suggestion.estimatedKcal.toString(),
            suggestion.proteinG.toBits().toString(),
            suggestion.carbsG.toBits().toString(),
            suggestion.fatG.toBits().toString(),
            suggestion.foods.size.toString(),
            *suggestion.foods.toTypedArray(),
        )

        /** Hashes request/suggestion identity only; raw nutrition text is never persisted or logged as an idempotency key. */
        private fun fingerprint(vararg values: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            val hash = digest.digest()
            return buildString(hash.size * 2) {
                hash.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(HEX_DIGITS[unsigned ushr 4])
                    append(HEX_DIGITS[unsigned and 0x0f])
                }
            }
        }

        private const val HEX_DIGITS = "0123456789abcdef"

        /**
         * Sentence, action and technical code for a failed advice job (`raw` is what the job wrote: a closed [com.myfitai.app.ai.AiFailure]
         * text or code). Like the BIA analysis, an answer rejected by the app's checks is a non deterministic model output, not a
         * permanent error of the request: the user's own tap on "Riprova" may work. No automatic retry (it would spend quota).
         */
        fun presentationFor(raw: String?): AiFailureMessages.Presentation {
            val resolved = AiFailureMessages.resolve(raw)
            return if (resolved.messageRes == R.string.ai_failure_rejected) resolved.copy(canRetry = true) else resolved
        }
    }
}
