package com.myfitai.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.WeeklyReviewEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.domain.review.WeeklyReviewService
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class WeeklyReviewViewModel(
    private val service: WeeklyReviewService,
    private val activeProfileStore: ActiveProfileStore,
    private val aiJobScheduler: AiJobScheduler,
) : ViewModel() {
    data class ReviewContent(
        val summary: String,
        val observations: List<String>,
        val nextWeekGuidance: List<String>,
    )

    data class State(
        val weekStart: LocalDate = lastCompletedMonday(),
        val loading: Boolean = true,
        val generating: Boolean = false,
        val review: WeeklyReviewEntity? = null,
        val content: ReviewContent? = null,
        val metrics: WeeklyReviewService.LocalMetrics? = null,
        val error: String? = null,
        val successMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            activeProfileStore.activeProfileId.collectLatest { reload() }
        }
        viewModelScope.launch {
            activeProfileStore.activeProfileId.collectLatest { profileId ->
                if (profileId <= 0L) return@collectLatest
                val weekKey = _state.value.weekStart.toEpochDay().toString()
                aiJobScheduler.observe(AiJobType.WEEKLY_REVIEW, profileId, weekKey).collect { info ->
                    when (info?.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> _state.value = _state.value.copy(generating = true)
                        WorkInfo.State.SUCCEEDED -> { _state.value = _state.value.copy(generating = false, successMessage = "Review aggiornata."); reload() }
                        WorkInfo.State.FAILED -> _state.value = _state.value.copy(generating = false, error = info.outputData.getString("error") ?: "Impossibile generare la review")
                        else -> Unit
                    }
                }
            }
        }
    }

    fun previousWeek() {
        if (_state.value.generating) return
        _state.value = _state.value.copy(weekStart = _state.value.weekStart.minusWeeks(1), successMessage = null)
        reload()
    }

    fun nextWeek() {
        if (_state.value.generating) return
        val next = _state.value.weekStart.plusWeeks(1)
        if (next.isAfter(lastCompletedMonday())) return
        _state.value = _state.value.copy(weekStart = next, successMessage = null)
        reload()
    }

    fun generate() {
        if (_state.value.generating) return
        val week = _state.value.weekStart
        _state.value = _state.value.copy(generating = true, error = null, successMessage = null)
        val profileId = activeProfileStore.currentIdOrNull() ?: return
        aiJobScheduler.enqueue(AiJobType.WEEKLY_REVIEW, profileId, week.toEpochDay().toString())
    }

    private fun reload() {
        val week = _state.value.weekStart
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val metrics = runCatching { service.buildLocalMetrics(week) }.getOrNull()
            val review = runCatching { service.loadExisting(week) }.getOrNull()
            _state.value = _state.value.copy(
                loading = false,
                metrics = metrics,
                review = review,
                content = review?.let(::parseContent),
            )
        }
    }

    private fun parseContent(entity: WeeklyReviewEntity): ReviewContent {
        val raw = entity.structuredJson ?: return ReviewContent(entity.summary, emptyList(), emptyList())
        return runCatching {
            val root = JSONObject(raw)
            fun list(name: String): List<String> {
                val array = root.optJSONArray(name) ?: return emptyList()
                return buildList { for (i in 0 until array.length()) add(array.optString(i)) }
            }
            ReviewContent(
                summary = root.optString("summary", entity.summary),
                observations = list("observations"),
                nextWeekGuidance = list("nextWeekGuidance"),
            )
        }.getOrElse { ReviewContent(entity.summary, emptyList(), emptyList()) }
    }

    class Factory(
        private val service: WeeklyReviewService,
        private val activeProfileStore: ActiveProfileStore,
        private val aiJobScheduler: AiJobScheduler,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WeeklyReviewViewModel::class.java))
            return WeeklyReviewViewModel(service, activeProfileStore, aiJobScheduler) as T
        }
    }

    companion object {
        private fun lastCompletedMonday(today: LocalDate = LocalDate.now()): LocalDate {
            val currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return currentMonday.minusWeeks(1)
        }
    }
}
