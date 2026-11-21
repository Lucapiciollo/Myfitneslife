package com.myfitai.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.DateRange
import com.myfitai.app.domain.food.NutritionPlanPeriod
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.calculation.PlanFingerprint
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.food.PlanPrerequisites
import com.myfitai.app.domain.profile.ProfileAgeRule
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.domain.calculation.OperationalNutritionMetrics
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.food.WeeklyPlanAiJobHandler
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private fun planWeekMonday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
private fun todayIndexInWeek(weekStart: LocalDate): Int {
    val today = LocalDate.now()
    return if (!today.isBefore(weekStart) && !today.isAfter(weekStart.plusDays(6))) (today.toEpochDay() - weekStart.toEpochDay()).toInt() else 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class FoodPlanViewModel(
    private val repository: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val generationService: NutritionPlanGenerationService,
    private val calculations: ProfileCalculationService,
    private val notificationScheduler: NotificationScheduler,
    private val consumptionRepository: FoodConsumptionRepository,
    private val aiJobScheduler: AiJobScheduler,
    private val profileRepository: UserProfileRepository,
    private val biaRepository: BiaRepository,
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val schedulePreferences: NutritionPlanSchedulePreferences,
    private val time: TimeProvider = SystemTimeProvider,
) : ViewModel() {
    data class CalorieReference(
        val bmrKcal: Double?,
        val tdeeKcal: Double?,
        val operationalTargetKcal: Double?,
        val operationalProteinG: Double?,
    )

    private data class SourceData(
        val weekStart: LocalDate,
        val snapshot: FoodPlanSnapshot?,
        val records: List<FoodConsumptionEntity>,
        val currentInputsFingerprint: String?,
        val profileAgeStatus: ProfileAgeRule.Status = ProfileAgeRule.Status.VALID,
    )
    data class GenerationState(
        val running: Boolean = false,
        val error: String? = null,
        /** True quando l'errore si risolve correggendo il profilo (apre l'editor profilo). */
        val fixProfile: Boolean = false,
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
        val calorieReference: CalorieReference = CalorieReference(null, null, null, null),
        /** UX7: impronta degli input nutrizionali di adesso; con quella del piano ([PlanStaleness]) decide l'avviso "dati cambiati". */
        val currentInputsFingerprint: String? = null,
        /** Stato dell'età del profilo salvato; diverso da VALID impedisce la generazione dei target. */
        val profileAgeStatus: ProfileAgeRule.Status = ProfileAgeRule.Status.VALID,
    )

    private val selectedWeekStart = MutableStateFlow(planWeekMonday(LocalDate.now()))
    private val selectedDayIndex = MutableStateFlow(todayIndexInWeek(selectedWeekStart.value))
    private val generationState = MutableStateFlow(GenerationState())
    private val calorieReference = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) flowOf(CalorieReference(null, null, null, null))
        else combine(
            profileRepository.profile(profileId),
            biaRepository.all(profileId),
            bodyMeasurementRepository.all(profileId),
        ) { _, _, _ ->
            // Same numbers for every day: the profile's TDEE and target.
            val result = calculations.profileSnapshot(profileId)?.calculation
            val metrics = OperationalNutritionMetrics.calculate(result)
            CalorieReference(result?.bmrKcal, metrics.tdeeKcal, metrics.targetKcal, metrics.proteinG)
        }
    }

    init {
        viewModelScope.launch {
            combine(activeProfileStore.activeProfileId, selectedWeekStart) { profileId, week -> profileId to week }
                .flatMapLatest { (profileId, week) ->
                    if (profileId <= 0L) flowOf(null)
                    else aiJobScheduler.observe(AiJobType.WEEKLY_PLAN, profileId, week.toEpochDay().toString())
                }.collect { info ->
                    when (info?.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> generationState.value = GenerationState(running = true)
                        WorkInfo.State.SUCCEEDED -> generationState.value = GenerationState(
                            successMessage = listOfNotNull("Piano generato e validato.", info.outputData.getString("notice")).joinToString(" "),
                        )
                        WorkInfo.State.FAILED -> {
                            val rawError = info.outputData.getString("error")
                            generationState.value = GenerationState(error = friendlyGenerationError(rawError), fixProfile = PlanPrerequisites.needsProfileEditor(rawError))
                        }
                        else -> Unit
                    }
                }
        }
    }

    private val source = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
            if (profileId <= 0L) flowOf(SourceData(selectedWeekStart.value, null, emptyList(), null))
        else selectedWeekStart.flatMapLatest { weekStart ->
            combine(
                repository.latestSnapshotForDisplayedWeek(profileId, weekStart.toEpochDay()),
                consumptionRepository.all(profileId),
                profileRepository.profile(profileId),
                biaRepository.all(profileId),
                bodyMeasurementRepository.all(profileId),
            ) { snapshot, records, profile, bia, bodyMeasurements ->
                SourceData(
                    weekStart,
                    snapshot,
                    records,
                    profile?.let { PlanFingerprint.of(it, bia, bodyMeasurements, time.today()) },
                    // Stessa fonte di data del servizio (TimeProvider); data assente = nessun avviso.
                    profile?.let { ProfileAgeRule.warningStatus(it.birthDateEpochDay, time.today()) } ?: ProfileAgeRule.Status.VALID,
                )
            }
        }
    }

    val state: StateFlow<State> = combine(source, selectedDayIndex, generationState, calorieReference) { sourceData, dayIndex, generation, calorieReference ->
        val (weekStart, snapshot, records, currentInputsFingerprint, profileAgeStatus) = sourceData
        val safeIndex = dayIndex.coerceIn(0, 6)
        State(
            weekStart = weekStart,
            snapshot = snapshot,
            selectedDayIndex = safeIndex,
            selectedDay = snapshot?.version?.days?.firstOrNull { it.dateEpochDay == weekStart.plusDays(safeIndex.toLong()).toEpochDay() },
            hasPlan = snapshot != null,
            generation = generation,
            consumptionRecords = records,
            calorieReference = calorieReference,
            currentInputsFingerprint = currentInputsFingerprint,
            profileAgeStatus = profileAgeStatus,
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
                aiJobScheduler.enqueue(
                    type = AiJobType.WEEKLY_PLAN,
                    profileId = profileId,
                    jobKey = week.toEpochDay().toString(),
                    replaceExisting = true,
                    params = androidx.work.workDataOf(
                        WeeklyPlanAiJobHandler.KEY_PERIOD_WEEKS to schedulePreferences.periodWeeks(profileId),
                    ),
                )
            }
        }
    }

    fun generateDateRange(range: DateRange) {
        if (generationState.value.running) return
        val validatedRange = NutritionPlanPeriod.validateSelectableRange(range, time.today())
            .getOrNull()
            ?.let { candidate -> runCatching { NutritionPlanPeriod.requireGenerationLength(candidate) }.getOrNull() }
        if (validatedRange == null) {
            generationState.value = GenerationState(error = "Seleziona un intervallo da oggi alla fine del mese corrente, lungo al massimo 31 giorni.")
            return
        }

        val week = NutritionPlanPeriod.monday(validatedRange.start)
        selectedWeekStart.value = week
        selectedDayIndex.value = todayIndexInWeek(week)
        generationState.value = GenerationState(running = true)
        viewModelScope.launch {
            val profileId = activeProfileStore.currentIdOrNull()
            if (profileId == null) {
                generationState.value = GenerationState(error = "Nessun profilo attivo")
            } else {
                aiJobScheduler.enqueue(
                    type = AiJobType.WEEKLY_PLAN,
                    profileId = profileId,
                    jobKey = week.toEpochDay().toString(),
                    replaceExisting = true,
                    params = androidx.work.workDataOf(
                        WeeklyPlanAiJobHandler.KEY_RANGE_START_EPOCH_DAY to validatedRange.start.toEpochDay(),
                        WeeklyPlanAiJobHandler.KEY_RANGE_END_EPOCH_DAY to validatedRange.endInclusive.toEpochDay(),
                    ),
                )
            }
        }
    }

    fun selectWeekAndDay(dateEpochDay: Long) {
        if (generationState.value.running) return
        val date = LocalDate.ofEpochDay(dateEpochDay)
        val monday = planWeekMonday(date)
        selectedWeekStart.value = monday
        selectedDayIndex.value = (date.toEpochDay() - monday.toEpochDay()).toInt().coerceIn(0, 6)
        clearGenerationMessage()
    }


    fun clearGenerationMessage() { if (!generationState.value.running) generationState.value = GenerationState() }

    private fun friendlyGenerationError(raw: String?): String = when {
        raw.isNullOrBlank() -> "Generazione non riuscita. Riprova a generare il piano."
        raw.startsWith("NUTRITION_INTEGRITY_INVALID") ->
            "L'IA ha prodotto un piano con calorie o struttura incoerenti anche dopo alcuni tentativi. Riprova a generare il piano."
        raw.startsWith("TARGET_TOLERANCE_EXCEEDED") ->
            "Il piano generato non rispetta il target del giorno. Dettagli: ${raw.substringAfter(':', "valori fuori tolleranza")}. Verifica anche le preferenze alimentari e riprova."
        raw.startsWith("DAY_TOTALS_INCONSISTENT") ->
            "Nel piano generato la somma dei pasti non coincide con i totali del giorno. Riprova a generare il piano."
        raw.startsWith("WEEK_MUST_HAVE_") || raw.startsWith("WEEK_DATES_INVALID") || raw.startsWith("WEEK_START_MISMATCH") || raw.contains("MEALS") || raw.contains("MEAL_COUNT") ->
             "Il piano generato è incompleto o mal strutturato (non copre tutti i giorni o i pasti previsti). Riprova a generare il piano."
        raw.startsWith("PLAN_REVIEW_") ->
            "La revisione automatica ha respinto il piano generato. Riprova a generare il piano."
        raw.startsWith("WEEKLY_VARIETY_DUPLICATE_RECIPE") ->
            "Il piano contiene alcuni pasti ripetuti, ma questa condizione non dovrebbe impedire il salvataggio. Riprova se il problema persiste."
         raw.startsWith("OUTPUT_TRUNCATED") ->
             "Il provider IA ha interrotto la risposta prima di completare il periodo del piano. Aggiorna l'app e riprova; nessun piano incompleto è stato salvato."
        raw.startsWith("INVALID_SCHEMA") || raw.startsWith("PIPE_") || raw.contains("INVALID_COMPACT_PROTOCOL") ->
            "La risposta dell'IA non era nel formato atteso anche dopo i tentativi di correzione. Riprova a generare il piano."
        raw.startsWith(PlanPrerequisites.PREFIX.trim()) -> raw
        raw.startsWith("NEEDS_INPUT") -> "Completa prima i dati richiesti nel profilo."
        raw == "PAST_WEEK_READ_ONLY" ->
            "Le settimane concluse sono di sola lettura e non possono essere rigenerate."
        raw.contains("non configurato", ignoreCase = true) ->
            "Nessun provider IA configurato. Configura Gemini o OpenAI nelle Impostazioni, poi genera il piano."
        raw.contains("raggiungibile", ignoreCase = true) || raw.contains("disponibile", ignoreCase = true) ->
            "$raw Riprova a generare il piano."
        else -> "Generazione non riuscita ($raw). Riprova a generare il piano."
    }.let { text ->
        // Il codice stabile dell'ultimo motivo resta visibile (diagnostica L6d), senza testo del modello.
        if (raw != null && GENERATION_CODE.matches(raw) && !text.contains(raw)) "$text [$raw]" else text
    }

    private val GENERATION_CODE = Regex("^[A-Z][A-Z0-9_]{2,59}$")

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
        private val calculations: ProfileCalculationService,
        private val notificationScheduler: NotificationScheduler,
        private val consumptionRepository: FoodConsumptionRepository,
        private val aiJobScheduler: AiJobScheduler,
        private val profileRepository: UserProfileRepository,
        private val biaRepository: BiaRepository,
        private val bodyMeasurementRepository: BodyMeasurementRepository,
        private val schedulePreferences: NutritionPlanSchedulePreferences,
        private val time: TimeProvider = SystemTimeProvider,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FoodPlanViewModel::class.java))
            return FoodPlanViewModel(repository, activeProfileStore, generationService, calculations, notificationScheduler, consumptionRepository, aiJobScheduler, profileRepository, biaRepository, bodyMeasurementRepository, schedulePreferences, time) as T
        }
    }
}
