package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.NutritionRecoveryRepository
import com.myfitai.app.data.repository.WorkoutEnergyExpenditureRepository
import com.myfitai.app.domain.food.NutritionRecoveryTargetEngine
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobState
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.calculation.EnergyTargetPresentation
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.notifications.NotificationScheduler
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind
import com.myfitai.app.ai.AiExecutionService
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
    private val profiles: UserProfileRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val generationService: NutritionPlanGenerationService,
    private val calculations: ProfileCalculationService,
    private val notificationScheduler: NotificationScheduler,
    private val consumptionRepository: FoodConsumptionRepository,
    private val cheatEntryRepository: CheatEntryRepository,
    private val recoveryRepository: NutritionRecoveryRepository,
    private val exerciseEnergyRepository: WorkoutEnergyExpenditureRepository,
    private val generationScheduler: AiJobScheduler,
) : ViewModel() {
    private data class SourceData(
        val weekStart: LocalDate,
        val snapshot: FoodPlanSnapshot?,
        val records: List<FoodConsumptionEntity>,
        val cheats: List<CheatEntryEntity>,
        val recovery: NutritionRecoveryTargetEngine.State? = null,
        val energy: EnergyTargetPresentation.State = EnergyTargetPresentation.State(mode = EnergyTargetPresentation.Mode.INSUFFICIENT_DATA),
        val exerciseKcalByDay: Map<Long, Int> = emptyMap(),
    )

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
        val cheatEntries: List<CheatEntryEntity> = emptyList(),
        val recovery: NutritionRecoveryTargetEngine.State? = null,
        val energy: EnergyTargetPresentation.State = EnergyTargetPresentation.State(mode = EnergyTargetPresentation.Mode.INSUFFICIENT_DATA),
        val exerciseKcalByDay: Map<Long, Int> = emptyMap(),
    )

    private val selectedWeekStart = MutableStateFlow(planWeekMonday(LocalDate.now()))
    private val selectedDayIndex = MutableStateFlow(todayIndexInWeek(selectedWeekStart.value))
    private val generationState = MutableStateFlow(GenerationState())
    private val refreshTick = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            activeProfileStore.activeProfileId.flatMapLatest { profileId ->
                if (profileId <= 0L) flowOf(AiJobState.Idle)
                else selectedWeekStart.flatMapLatest { week ->
                    generationScheduler.observe(AiJobType.WEEKLY_PLAN, profileId, jobKey(week))
                }
            }.collect { applyGenerationState(it) }
        }
    }

    private val source = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) flowOf(SourceData(selectedWeekStart.value, null, emptyList(), emptyList(), null))
        else selectedWeekStart.flatMapLatest { weekStart ->
            refreshTick.flatMapLatest {
                combine(profiles.profile(profileId), repository.plans(profileId), consumptionRepository.all(profileId), cheatEntryRepository.all(profileId), recoveryRepository.events(profileId)) { profile, _, records, cheats, events ->
                val snapshot = repository.loadLatestSnapshot(profileId, weekStart.toEpochDay())
                val exerciseKcalByDay = exerciseEnergyRepository.forRange(profileId, weekStart.toEpochDay(), weekStart.plusDays(6).toEpochDay())
                    .groupBy { it.exerciseDateEpochDay }.mapValues { (_, values) -> values.sumOf { it.caloriesKcal.coerceAtLeast(0) } }
                val recovery = snapshot?.version?.targetKcal?.let { target ->
                    val day = LocalDate.now().toEpochDay()
                    NutritionRecoveryTargetEngine.calculate(
                        target,
                        events,
                        day,
                        recoveryRepository.withdrawal(profileId, day),
                        recoveryRepository.plannedBefore(profileId, day),
                        exerciseKcal = exerciseKcalByDay[day] ?: 0,
                    )
                }
                val calculation = calculations.profileSnapshot(profileId)?.calculation
                    SourceData(
                    weekStart = weekStart,
                    snapshot = snapshot,
                    records = records,
                    cheats = cheats,
                    recovery = recovery,
                    energy = EnergyTargetPresentation.build(
                        calculation = calculation,
                        recovery = recovery,
                        goal = ProfileCalculationMapper.goal(profile?.goal),
                    ),
                    exerciseKcalByDay = exerciseKcalByDay,
                    )
                }
            }
        }
    }

    val state: StateFlow<State> = combine(source, selectedDayIndex, generationState) { sourceValue, dayIndex, generation ->
        val weekStart = sourceValue.weekStart
        val snapshot = sourceValue.snapshot
        val records = sourceValue.records
        val safeIndex = dayIndex.coerceIn(0, 6)
        State(
            weekStart = weekStart,
            snapshot = snapshot,
            selectedDayIndex = safeIndex,
            selectedDay = snapshot?.version?.days?.firstOrNull { day -> day.dateEpochDay == weekStart.plusDays(safeIndex.toLong()).toEpochDay() },
            hasPlan = snapshot != null,
            generation = generation,
            consumptionRecords = records,
            cheatEntries = sourceValue.cheats,
            recovery = sourceValue.recovery,
            energy = sourceValue.energy,
            exerciseKcalByDay = sourceValue.exerciseKcalByDay,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

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
        val profileId = activeProfileStore.currentIdOrNull() ?: return
        generationState.value = GenerationState(running = true)
        generationScheduler.enqueue(AiJobType.WEEKLY_PLAN, profileId, jobKey(week))
    }

    fun cancelGeneration() {
        if (!generationState.value.running) return
        val profileId = activeProfileStore.currentIdOrNull() ?: return
        generationScheduler.cancel(AiJobType.WEEKLY_PLAN, profileId, jobKey(selectedWeekStart.value))
        generationState.value = GenerationState(error = "Generazione annullata")
    }

    private fun jobKey(week: LocalDate): String = week.toEpochDay().toString()

    private suspend fun applyGenerationState(workState: AiJobState) {
        when (workState) {
            AiJobState.Idle -> Unit
            AiJobState.Running -> generationState.value = GenerationState(running = true)
            is AiJobState.Succeeded -> {
                generationScheduler.consume(workState.id)
                runCatching { notificationScheduler.refresh() }
                generationState.value = GenerationState(successMessage = "Piano generato con ${workState.provider}", usageMessage = "Piano validato localmente e aggiornato automaticamente")
                refreshTick.value++
            }
            is AiJobState.Failed -> {
                generationScheduler.consume(workState.id)
                generationState.value = GenerationState(error = workState.message)
                refreshTick.value++
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

    private fun friendlyGenerationError(message: String?): String = when {
        message == "DAY_TOTALS_INCONSISTENT" -> "i totali giornalieri non coincidono con i pasti"
        message?.contains("PIPE_I_INVALID") == true -> "un ingrediente contiene separatori non validi"
        message?.contains("TARGET_TOLERANCE_EXCEEDED") == true -> "i valori sono fuori dal target nutrizionale"
        message?.contains("INVALID_SCHEMA") == true -> "il formato JSON/pipe non è valido"
        else -> "controllo locale fallito"
    }

    class Factory(
        private val repository: MealPlanRepository,
        private val profiles: UserProfileRepository,
        private val activeProfileStore: ActiveProfileStore,
        private val generationService: NutritionPlanGenerationService,
        private val calculations: ProfileCalculationService,
        private val notificationScheduler: NotificationScheduler,
        private val consumptionRepository: FoodConsumptionRepository,
        private val cheatEntryRepository: CheatEntryRepository,
        private val recoveryRepository: NutritionRecoveryRepository,
        private val exerciseEnergyRepository: WorkoutEnergyExpenditureRepository,
        private val generationScheduler: AiJobScheduler,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FoodPlanViewModel::class.java))
            return FoodPlanViewModel(repository, profiles, activeProfileStore, generationService, calculations, notificationScheduler, consumptionRepository, cheatEntryRepository, recoveryRepository, exerciseEnergyRepository, generationScheduler) as T
        }
    }
}
