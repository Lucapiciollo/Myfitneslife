package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import androidx.work.WorkInfo
import com.myfitai.app.notifications.NotificationScheduler
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private fun planWeekMonday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
private fun todayIndexInWeek(weekStart: LocalDate): Int {
    val today = LocalDate.now()
    return if (!today.isBefore(weekStart) && !today.isAfter(weekStart.plusDays(6))) (today.toEpochDay() - weekStart.toEpochDay()).toInt() else 0
}

class FoodPlanViewModel(
    private val repository: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val generationService: NutritionPlanGenerationService,
    private val notificationScheduler: NotificationScheduler,
    private val consumptionRepository: FoodConsumptionRepository,
    private val aiJobScheduler: AiJobScheduler,
) : ViewModel() {
    data class GenerationState(
        val running: Boolean = false,
        val error: String? = null,
        val successMessage: String? = null,
        val usageMessage: String? = null,
    )
    data class State(
        val weekStart: LocalDate = planWeekMonday(LocalDate.now()),
        val snapshot: FoodPlanSnapshot? = null,
        val selectedDayIndex: Int = 0,
        val selectedDay: FoodPlanDay? = null,
        val hasPlan: Boolean = false,
        val generation: GenerationState = GenerationState(),
        val consumptionRecords: List<FoodConsumptionEntity> = emptyList(),
    )

    private val selectedWeekStart = MutableStateFlow(planWeekMonday(LocalDate.now()))
    private val selectedDayIndex = MutableStateFlow(todayIndexInWeek(selectedWeekStart.value))
    private val generationState = MutableStateFlow(GenerationState())

    init {
        viewModelScope.launch {
            combine(activeProfileStore.activeProfileId, selectedWeekStart) { profileId, week -> profileId to week }
                .flatMapLatest { (profileId, week) ->
                    if (profileId <= 0L) flowOf(null)
                    else aiJobScheduler.observe(AiJobType.WEEKLY_PLAN, profileId, week.toEpochDay().toString())
                }.collect { info ->
                    when (info?.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> generationState.value = GenerationState(running = true)
                        WorkInfo.State.SUCCEEDED -> generationState.value = GenerationState(successMessage = "Piano generato e validato.")
                        WorkInfo.State.FAILED -> generationState.value = GenerationState(error = info.outputData.getString("error") ?: "Generazione non riuscita")
                        else -> Unit
                    }
                }
        }
    }

    private val source = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) flowOf<Triple<LocalDate, FoodPlanSnapshot?, List<FoodConsumptionEntity>>>(Triple(selectedWeekStart.value, null, emptyList()))
        else selectedWeekStart.flatMapLatest { weekStart ->
            combine(repository.plans(profileId), consumptionRepository.all(profileId)) { _, records ->
                Triple(weekStart, repository.loadLatestSnapshot(profileId, weekStart.toEpochDay()), records)
            }
        }
    }

    val state: StateFlow<State> = combine(source, selectedDayIndex, generationState) { (weekStart, snapshot, records), dayIndex, generation ->
        val safeIndex = dayIndex.coerceIn(0, 6)
        State(
            weekStart = weekStart,
            snapshot = snapshot,
            selectedDayIndex = safeIndex,
            selectedDay = snapshot?.version?.days?.firstOrNull { it.dateEpochDay == weekStart.plusDays(safeIndex.toLong()).toEpochDay() },
            hasPlan = snapshot != null,
            generation = generation,
            consumptionRecords = records,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun selectWeek(weekStartEpochDay: Long) {
        if (generationState.value.running) return
        selectedWeekStart.value = planWeekMonday(LocalDate.ofEpochDay(weekStartEpochDay))
        selectedDayIndex.value = todayIndexInWeek(selectedWeekStart.value)
        clearGenerationMessage()
    }

    fun previousWeek() { if (!generationState.value.running) { selectedWeekStart.value = selectedWeekStart.value.minusWeeks(1); selectedDayIndex.value = 0; clearGenerationMessage() } }
    fun nextWeek() { if (!generationState.value.running) { selectedWeekStart.value = selectedWeekStart.value.plusWeeks(1); selectedDayIndex.value = 0; clearGenerationMessage() } }
    fun selectDay(index: Int) { selectedDayIndex.value = index.coerceIn(0, 6) }

    fun generateCurrentWeek() {
        if (generationState.value.running) return
        val week = selectedWeekStart.value
        generationState.value = GenerationState(running = true)
        viewModelScope.launch {
            val profileId = activeProfileStore.currentIdOrNull()
            if (profileId == null) {
                generationState.value = GenerationState(error = "Nessun profilo attivo")
            } else {
                aiJobScheduler.enqueue(AiJobType.WEEKLY_PLAN, profileId, week.toEpochDay().toString())
            }
        }
    }

    fun clearGenerationMessage() { if (!generationState.value.running) generationState.value = GenerationState() }

    private fun providerLimitMessage(error: AiTransportException.Http): String = when (error.failureKind) {
        AiTransportFailureKind.QUOTA_EXHAUSTED -> {
            val limit = error.quotaLimit?.let { " Limite rilevato: $it richieste/giorno." }.orEmpty()
            "Quota ${error.provider.name} esaurita: 0 richieste disponibili.$limit Attendi il reset della quota o configura un piano con billing."
        }
        AiTransportFailureKind.RATE_LIMITED -> {
            val seconds = error.retryAfterSeconds
            if (seconds == null) {
                "Troppe richieste ravvicinate a ${error.provider.name}. Riprova più tardi."
            } else {
                "Limite temporaneo ${error.provider.name}: riprova tra ${formatRetryDelay(seconds)}."
            }
        }
        else -> "Errore HTTP provider: ${error.statusCode}"
    }

    private fun formatRetryDelay(seconds: Long): String = when {
        seconds < 60 -> "$seconds secondi"
        else -> "circa ${((seconds + 59) / 60)} minuti"
    }

    class Factory(
        private val repository: MealPlanRepository,
        private val activeProfileStore: ActiveProfileStore,
        private val generationService: NutritionPlanGenerationService,
        private val notificationScheduler: NotificationScheduler,
        private val consumptionRepository: FoodConsumptionRepository,
        private val aiJobScheduler: AiJobScheduler,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FoodPlanViewModel::class.java))
            return FoodPlanViewModel(repository, activeProfileStore, generationService, notificationScheduler, consumptionRepository, aiJobScheduler) as T
        }
    }
}
