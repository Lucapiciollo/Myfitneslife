package com.myfitai.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.BodyExpectationGoalEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CalorieRecoveryRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.MealPlanWeekSelector
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.BodyExpectationGoalRepository
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.calculation.WeightResolver
import com.myfitai.app.domain.calculation.WeightSource
import com.myfitai.app.domain.calculation.OperationalNutritionMetrics
import com.myfitai.app.domain.calculation.WeeklyBodyExpectation
import com.myfitai.app.domain.display.DayChange
import com.myfitai.app.domain.display.DayTargetResolver
import com.myfitai.app.domain.display.WeeklyMaintenance
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.domain.food.CalorieRecoveryEngine
import com.myfitai.app.domain.food.FoodConsumptionMetrics
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.progress.ProgressSeriesEngine
import com.myfitai.app.domain.progress.ProgressSeriesPoint
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
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val profiles: UserProfileRepository,
    private val biaRepository: BiaRepository,
    private val bodyRepository: BodyMeasurementRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val cheatRepository: CheatEntryRepository,
    private val recoveryRepository: CalorieRecoveryRepository,
    private val foodConsumptionRepository: FoodConsumptionRepository,
    private val bodyExpectationGoalRepository: BodyExpectationGoalRepository,
    private val nutritionPlanSchedulePreferences: NutritionPlanSchedulePreferences,
    private val activeProfileStore: ActiveProfileStore,
    private val profileCalculationService: ProfileCalculationService,
    private val time: TimeProvider = SystemTimeProvider,
) : ViewModel() {

    /** [source] e la provenienza del dato (per grasso e muscolo e sempre BIA); l'etichetta testuale e in strings.xml. */
    data class MetricState(val value: Float?, val deltaFromPrevious: Float?, val source: WeightSource? = null)
    data class TrendPoint(val timestamp: Long, val value: Float)
    data class TrendSeries(val label: String, val points: List<TrendPoint>)
    data class CalorieState(
        val bmr: Int? = null,
        val biaBmr: Int? = null,
        val tdee: Int? = null,
        val target: Int? = null,
        val goalLabel: String? = null,
        val energyPercent: Int? = null,
        val targetFromCurrentPlan: Boolean = false,
        val targetFromWeeklyPlan: Boolean = false,
        val targetBeforeAdaptation: Int? = null,
        /** Profile + activity target, without plan adaptation or recovery; shown only as a labelled reference. */
        val theoreticalTarget: Int? = null,
        /** Where [target] comes from: the plan day (adaptation and recovery included) or the theoretical figure. */
        val targetSource: DayTargetResolver.Source = DayTargetResolver.Source.NONE,
        val planTarget: Int? = null,
        val habitualTdee: Int? = null,
        val consumedKcal: Int? = null,
        val consumedProteinG: Double? = null,
        val proteinTargetG: Double? = null,
        val consumedCount: Int = 0,
        val recordedCount: Int = 0,
        /** UX7: impronta degli input di adesso e quella del piano mostrato; con [PlanStaleness] danno l'unico avviso "piano non aggiornato". */
        val currentInputsFingerprint: String? = null,
        val planInputsFingerprint: String? = null,
    )
    data class NextMealState(
        val mealId: Long,
        val dateEpochDay: Long,
        val timeMinutes: Int?,
        val title: String,
        val type: String,
        val kcal: Int?,
    )

    data class RecoveryState(val pendingKcal: Int = 0, val creditCount: Int = 0, val nextExpiry: LocalDate? = null)
    data class DashboardState(
        val loading: Boolean = true,
        /** The day the numbers refer to; it changes at midnight so the screen re-renders even with identical values. */
        val todayEpochDay: Long = 0L,
        val profileName: String? = null,
        val goal: String? = null,
        val weight: MetricState = MetricState(null, null),
        val bodyFat: MetricState = MetricState(null, null),
        val muscleMass: MetricState = MetricState(null, null),
        val trendSeries: List<TrendSeries> = emptyList(),
        val bodyMeasurementTrendSeries: List<TrendSeries> = emptyList(),
        val recompositionState: LocalCalculationEngine.RecompositionState = LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA,
        val calories: CalorieState = CalorieState(),
        val nextMeal: NextMealState? = null,
        val upcomingMeals: List<NextMealState> = emptyList(),
        val consumptionRecords: List<FoodConsumptionEntity> = emptyList(),
        val recovery: RecoveryState = RecoveryState(),
        val latestBiaTimestamp: Long? = null,
        val weeklyExpectation: WeeklyBodyExpectation.Result = WeeklyBodyExpectation.Result(available = false),
        val expectationGoals: List<BodyExpectationGoalEntity> = emptyList(),
    )

    private data class Source(
        val profile: UserProfileEntity?,
        val bia: List<BiaMeasurementEntity>,
        val body: List<BodyMeasurementEntity>,
    )

    // Home's selector is initialized to 1Y; keep the chart query on the same range from first render.
    private val selectedRange = MutableStateFlow(3)

    // Today as a flow: it changes at midnight, so no source below keeps the day the screen was created on.
    private val today: StateFlow<LocalDate> =
        DayChange.todayFlow(time).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), time.today())

    private val profileAndDay = combine(activeProfileStore.activeProfileId, today) { profileId, day -> profileId to day }

    private val source = profileAndDay.flatMapLatest { (profileId, _) ->
        if (profileId <= 0L) {
            flowOf(Source(null, emptyList(), emptyList()))
        } else {
            combine(
                profiles.profile(profileId),
                biaRepository.all(profileId),
                bodyRepository.all(profileId),
            ) { profile, bia, body -> Source(profile, bia, body) }
        }
    }

    private val upcomingMealsSource = profileAndDay.flatMapLatest { (profileId, _) ->
        if (profileId <= 0L) {
            flowOf<List<NextMealState>>(emptyList())
        } else {
            mealPlanRepository.plans(profileId).flatMapLatest { plans ->
                flow { emit(findUpcomingMeals(profileId, plans.map { it.weekStartEpochDay }, limit = 2)) }
            }
        }
    }

    private val recoverySource = profileAndDay.flatMapLatest { (profileId, day) ->
        if (profileId <= 0L) {
            flowOf(RecoveryState())
        } else {
            val zone = time.zoneId
            val windowStart = day.minusDays(CalorieRecoveryEngine.WINDOW_DAYS)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val now = time.nowEpochMillis()
            cheatRepository.between(profileId, windowStart, now).map { entries ->
                computeRecovery(profileId, entries)
            }
        }
    }

    private val consumedTodaySource = profileAndDay.flatMapLatest { (profileId, day) ->
        if (profileId <= 0L) {
            flowOf(emptyList())
        } else {
            // Consumption is a historical fact. Keep counting it if the weekly plan
            // gets a new version after the user marked a meal as consumed.
            foodConsumptionRepository.forDay(profileId, day.toEpochDay())
        }
    }

    // Same plan-for-the-week rule as the Food screen: a multi-week period is stored on its first week,
    // so week 2..N must fall back to the plan whose days cover it. Versions are observed too.
    private val weeklyPlanSource = profileAndDay.flatMapLatest { (profileId, day) ->
        if (profileId <= 0L) {
            flowOf<com.myfitai.app.domain.food.FoodPlanSnapshot?>(null)
        } else {
            val weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
            mealPlanRepository.latestSnapshotForDisplayedWeek(profileId, weekStart)
        }
    }

    private val expectationGoalsSource = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) flowOf(emptyList()) else bodyExpectationGoalRepository.all(profileId)
    }

    val state: StateFlow<DashboardState> = combine(
        source,
        selectedRange,
        upcomingMealsSource,
        recoverySource,
        consumedTodaySource,
    ) { source, rangeIndex, upcomingMeals, recovery, consumption ->
        buildState(source, rangeIndex, upcomingMeals, recovery, consumption)
    }.combine(weeklyPlanSource) { dashboard, snapshot ->
        val todayDate = today.value
        val monday = todayDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val todayTargetDay = snapshot?.version?.days
            ?.firstOrNull { it.dateEpochDay == todayDate.toEpochDay() }
        val todayBaseTarget = todayTargetDay?.baseTargetKcal ?: todayTargetDay?.targetKcal ?: snapshot?.version?.targetKcal
        val expectationWeeks = activeProfileStore.currentIdOrNull()?.let(nutritionPlanSchedulePreferences::periodWeeks) ?: 1
        // ONE target for the day, shared with Food: the plan day's (adaptation and recovery included) when the
        // plan has it, otherwise the theoretical one (dashboard.calories.target is the theoretical figure here).
        val resolved = DayTargetResolver.resolve(
            theoreticalKcal = dashboard.calories.target?.toDouble(),
            theoreticalProteinG = dashboard.calories.proteinTargetG,
            planDayKcal = todayTargetDay?.targetKcal,
            planDayProteinG = (todayTargetDay?.targetProteinG ?: snapshot?.version?.targetProteinG)?.toDouble(),
            planWeekKcal = snapshot?.version?.targetKcal,
        )
        val planTarget = todayTargetDay?.targetKcal ?: snapshot?.version?.targetKcal ?: dashboard.calories.planTarget ?: dashboard.calories.target
        val displayedTarget = resolved.kcal
        val displayedTargetPercent = if (displayedTarget != null && dashboard.calories.tdee != null && dashboard.calories.tdee > 0) {
            Math.round((displayedTarget - dashboard.calories.tdee) * 100.0 / dashboard.calories.tdee).toInt()
        } else null
        val todayRecords = FoodConsumptionMetrics.recordsForDate(
            dashboard.consumptionRecords,
            todayDate.toEpochDay(),
        )
        val consumed = FoodConsumptionMetrics.dayTotals(todayRecords)
        val consumedRows = todayRecords.filter {
            it.status == com.myfitai.app.domain.food.FoodConsumptionStatus.CONSUMED.name
        }
        val consumedKcal = consumedRows.takeIf { rows -> rows.isNotEmpty() && rows.all { it.kcal != null } }
            ?.sumOf { it.kcal!!.toDouble() }
        val consumedProtein = consumedRows.takeIf { rows -> rows.isNotEmpty() && rows.all { it.proteinG != null } }
            ?.sumOf { it.proteinG!!.toDouble() }
        dashboard.copy(
            todayEpochDay = todayDate.toEpochDay(),
            calories = dashboard.calories.copy(
                target = displayedTarget,
                theoreticalTarget = resolved.theoreticalKcal,
                targetSource = resolved.source,
                targetFromCurrentPlan = todayTargetDay != null,
                targetFromWeeklyPlan = snapshot != null,
                targetBeforeAdaptation = todayBaseTarget,
                planTarget = planTarget,
                energyPercent = displayedTargetPercent,
                consumedKcal = consumedKcal?.let { Math.round(it).toInt() },
                consumedProteinG = consumedProtein,
                proteinTargetG = resolved.proteinG,
                consumedCount = consumed.consumedCount,
                recordedCount = consumed.recordedCount,
                planInputsFingerprint = snapshot?.version?.inputsFingerprint,
            ),
            weeklyExpectation = WeeklyBodyExpectation.calculate(
                // One TDEE (the profile's) for every day of the week.
                maintenanceKcal = WeeklyMaintenance.baseKcal(dashboard.calories.habitualTdee, dashboard.calories.tdee),
                weekStartEpochDay = monday.toEpochDay(),
                plannedDays = snapshot?.version?.days.orEmpty().map { it.dateEpochDay to it.totalKcal },
                periodWeeks = expectationWeeks,
            ),
        )
    }.combine(expectationGoalsSource) { dashboard, goals ->
        dashboard.copy(expectationGoals = goals)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun selectRange(index: Int) { selectedRange.value = index.coerceIn(0, 3) }

    fun confirmExpectation(status: String, note: String? = null) {
        val profileId = activeProfileStore.currentIdOrNull() ?: return
        val result = state.value.weeklyExpectation
        val monday = time.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = bodyExpectationGoalRepository.getForPeriod(profileId, monday.toEpochDay())
            bodyExpectationGoalRepository.upsert(
                BodyExpectationGoalEntity(
                    id = existing?.id ?: 0L,
                    profileId = profileId,
                    periodStartEpochDay = monday.toEpochDay(),
                    periodEndEpochDay = monday.plusWeeks(result.periodWeeks.toLong()).minusDays(1).toEpochDay(),
                    plannedDays = result.plannedDays,
                    theoreticalDeficitKcal = result.theoreticalDeficitKcal,
                    expectedFatLossMinKg = result.expectedFatLossKgMin,
                    expectedFatLossMaxKg = result.expectedFatLossKgMax,
                    initialWeightKg = existing?.initialWeightKg,
                    finalWeightKg = existing?.finalWeightKg,
                    status = status,
                    note = note,
                    createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = now,
                ),
            )
        }
    }

    private suspend fun buildState(
        source: Source,
        rangeIndex: Int,
        upcomingMeals: List<NextMealState>,
        recovery: RecoveryState,
        consumption: List<FoodConsumptionEntity>,
    ): DashboardState {
        val fatValues = metricValues(source.bia) { it.bodyFatPercent }
        val muscleValues = metricValues(source.bia) { it.muscleMassKg }
        val rangePeriod = rangePeriod(rangeIndex)
        // Valore, fonte, delta e serie del grafico derivano dalla stessa serie WeightResolver.
        val weightSeriesData = WeightResolver.series(
            WeightResolver.observations(source.bia, source.body, source.profile?.id),
            source.profile?.currentWeightKg,
        ) { points -> filterRange(points, rangePeriod) }
        val fatInRange = filterRange(fatValues, rangePeriod)
        val muscleInRange = filterRange(muscleValues, rangePeriod)
        val fatTrend = LocalCalculationEngine.trend(fatInRange.map { LocalCalculationEngine.TimedValue(it.first, it.second.toDouble()) }, metricKey = "bodyFatPercent")
        val muscleTrend = LocalCalculationEngine.trend(muscleInRange.map { LocalCalculationEngine.TimedValue(it.first, it.second.toDouble()) }, metricKey = "muscleMassKg")
        val weightSeries = weightSeriesData.points.map { it.second }
        val fatSeries = fatInRange.map { it.second }
        val muscleSeries = muscleInRange.map { it.second }
        val bodyMeasurementTrendSeries = bodyMeasurementTrendSeries(source, rangePeriod)
        val calories = calorieState(source)

        return DashboardState(
            loading = false,
            profileName = source.profile?.name,
            goal = source.profile?.goal,
            weight = MetricState(weightSeriesData.reading.valueKg, weightSeriesData.deltaFromPrevious, weightSeriesData.reading.source),
            bodyFat = metricState(fatInRange).copy(
                value = fatValues.maxByOrNull { it.first }?.second,
                source = WeightSource.BIA.takeIf { fatValues.isNotEmpty() },
            ),
            muscleMass = metricState(muscleInRange).copy(
                value = muscleValues.maxByOrNull { it.first }?.second,
                source = WeightSource.BIA.takeIf { muscleValues.isNotEmpty() },
            ),
            trendSeries = listOf(
                TrendSeries("Peso", weightSeries.map { TrendPoint(0L, it) }),
                TrendSeries("Grasso corporeo", fatSeries.map { TrendPoint(0L, it) }),
                TrendSeries("Massa muscolare", muscleSeries.map { TrendPoint(0L, it) }),
            ),
            bodyMeasurementTrendSeries = bodyMeasurementTrendSeries(source, rangePeriod),
            recompositionState = LocalCalculationEngine.classifyRecomposition(fatTrend.delta, muscleTrend.delta),
            calories = calories,
            nextMeal = upcomingMeals.firstOrNull(),
            upcomingMeals = upcomingMeals,
            consumptionRecords = consumption,
            recovery = recovery,
            latestBiaTimestamp = source.bia.maxByOrNull { it.measuredAtEpochMillis }?.measuredAtEpochMillis,
        )
    }

    private suspend fun calorieState(source: Source): CalorieState {
        val profile = source.profile ?: return CalorieState()
        val latestBia = source.bia.maxByOrNull { it.measuredAtEpochMillis }
        val snapshot = profileCalculationService.profileSnapshot(profile.id)
        val calculation = snapshot?.calculation
        val metrics = OperationalNutritionMetrics.calculate(calculation)
        val tdeeInt = metrics.tdeeKcal?.let { Math.round(it).toInt() }
        val targetInt = metrics.targetKcal?.let { Math.round(it).toInt() }
        val energyPercent = if (tdeeInt != null && targetInt != null && tdeeInt > 0) {
            Math.round((targetInt - tdeeInt) * 100.0 / tdeeInt).toInt()
        } else {
            null
        }
        return CalorieState(
            bmr = metrics.bmrKcal?.let { Math.round(it).toInt() },
            biaBmr = latestBia?.bmrKcal?.let { Math.round(it).toInt() },
            tdee = tdeeInt,
            target = targetInt,
            proteinTargetG = metrics.proteinG,
            planTarget = targetInt,
            habitualTdee = tdeeInt,
            goalLabel = goalLabel(ProfileCalculationMapper.goal(profile.goal)),
            energyPercent = energyPercent,
            currentInputsFingerprint = snapshot?.inputsFingerprint,
        )
    }

    private fun goalLabel(goal: LocalCalculationEngine.Goal?): String? = when (goal) {
        LocalCalculationEngine.Goal.WEIGHT_LOSS -> "Dimagrimento"
        LocalCalculationEngine.Goal.RECOMPOSITION -> "Ricomposizione"
        LocalCalculationEngine.Goal.MAINTENANCE -> "Mantenimento"
        LocalCalculationEngine.Goal.MUSCLE_GAIN -> "Aumento massa"
        LocalCalculationEngine.Goal.PERFORMANCE -> "Performance"
        null -> null
    }

    private suspend fun findUpcomingMeals(profileId: Long, planWeekStarts: List<Long>, limit: Int): List<NextMealState> {
        val today = time.today()
        val nowMinutes = time.currentTime().let { it.hour * 60 + it.minute }
        val currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        // Same plan-for-the-week rule as Food; a period plan can answer for both weeks, so de-duplicate by version.
        val snapshots = listOf(currentWeek, currentWeek.plusWeeks(1)).mapNotNull { week ->
            MealPlanWeekSelector.pick(
                planWeekStarts = planWeekStarts,
                weekStartEpochDay = week.toEpochDay(),
                load = { start -> mealPlanRepository.loadLatestSnapshot(profileId, start) },
                coveredDays = { snapshot -> snapshot.version.days.map { it.dateEpochDay } },
            )
        }.distinctBy { it.version.id }

        val result = mutableListOf<NextMealState>()
        for (snapshot in snapshots) {
            for (day in snapshot.version.days.sortedBy { it.dateEpochDay }) {
                val date = LocalDate.ofEpochDay(day.dateEpochDay)
                if (date.isBefore(today)) continue
                val meals = day.meals.sortedWith(compareBy<FoodMeal> { it.timeMinutes ?: Int.MAX_VALUE }.thenBy { it.sortOrder })
                for (meal in meals) {
                    val upcoming = date.isAfter(today) || meal.timeMinutes == null || meal.timeMinutes >= nowMinutes
                    if (!upcoming) continue
                    result += NextMealState(
                        mealId = meal.id,
                        dateEpochDay = day.dateEpochDay,
                        timeMinutes = meal.timeMinutes,
                        title = meal.title,
                        type = meal.type,
                        kcal = meal.kcal,
                    )
                    if (result.size >= limit) return result
                }
            }
        }
        return result
    }

    private suspend fun computeRecovery(
        profileId: Long,
        entries: List<com.myfitai.app.data.local.entity.CheatEntryEntity>,
    ): RecoveryState {
        val monday = time.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var pending = 0
        var credits = 0
        var nextExpiry: LocalDate? = null
        for (entry in entries) {
            val totalKcal = entry.estimatedKcal?.takeIf { it > 0 } ?: continue
            if (recoveryRepository.wasCheatAdapted(profileId, entry.id)) continue
            val allocated = recoveryRepository.plannedRecoveryKcalOutsideWeek(
                profileId = profileId,
                cheatId = entry.id,
                currentWeekStartEpochDay = monday.toEpochDay(),
            )
            val remaining = (totalKcal - allocated).coerceAtLeast(0)
            if (remaining > 0) {
                pending += remaining
                credits++
                val expiry = Instant.ofEpochMilli(entry.occurredAtEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(CalorieRecoveryEngine.WINDOW_DAYS)
                nextExpiry = listOfNotNull(nextExpiry, expiry).minOrNull()
            }
        }
        return RecoveryState(pendingKcal = pending, creditCount = credits, nextExpiry = nextExpiry)
    }

    private fun bodyMeasurementTrendSeries(source: Source, range: Period): List<TrendSeries> {
        val metrics = listOf(
            "Peso" to (source.bia.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } } +
                source.body.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } }),
            "Grasso corporeo" to source.bia.mapNotNull { row ->
                row.bodyFatPercent?.let { row.measuredAtEpochMillis to it }
            },
            "Acqua corporea" to source.bia.mapNotNull { row ->
                row.bodyWaterPercent?.let { row.measuredAtEpochMillis to it }
            },
            "Fianchi" to source.body.mapNotNull { row -> row.hipsCm?.let { row.measuredAtEpochMillis to it } },
            "Vita" to source.body.mapNotNull { row -> row.waistCm?.let { row.measuredAtEpochMillis to it } },
            "Torace" to source.body.mapNotNull { row -> row.chestCm?.let { row.measuredAtEpochMillis to it } },
            "Addome" to source.body.mapNotNull { row -> row.abdomenCm?.let { row.measuredAtEpochMillis to it } },
            "Spalle" to source.body.mapNotNull { row -> row.shouldersCm?.let { row.measuredAtEpochMillis to it } },
            "Glutei" to source.body.mapNotNull { row -> row.glutesCm?.let { row.measuredAtEpochMillis to it } },
            "Braccio sinistro" to source.body.mapNotNull { row -> row.armLeftCm?.let { row.measuredAtEpochMillis to it } },
            "Braccio destro" to source.body.mapNotNull { row -> row.armRightCm?.let { row.measuredAtEpochMillis to it } },
            "Coscia sinistra" to source.body.mapNotNull { row -> row.thighLeftCm?.let { row.measuredAtEpochMillis to it } },
            "Coscia destra" to source.body.mapNotNull { row -> row.thighRightCm?.let { row.measuredAtEpochMillis to it } },
            "Polpaccio sinistro" to source.body.mapNotNull { row -> row.calfLeftCm?.let { row.measuredAtEpochMillis to it } },
            "Polpaccio destro" to source.body.mapNotNull { row -> row.calfRightCm?.let { row.measuredAtEpochMillis to it } },
        )
        // Keep every supported metric in the selector even when the selected range
        // has no reading for it. The chart itself becomes empty for that metric.
        return metrics.map { (label, values) ->
            val filtered = filterRange(values.sortedByDescending { it.first }, range)
            TrendSeries(label, filtered.map { TrendPoint(it.first, it.second) })
        }
    }

    private fun metricValues(history: List<BiaMeasurementEntity>, selector: (BiaMeasurementEntity) -> Float?): List<Pair<Long, Float>> =
        history.mapNotNull { row -> selector(row)?.let { row.measuredAtEpochMillis to it } }

    private fun filterRange(values: List<Pair<Long, Float>>, range: Period): List<Pair<Long, Float>> {
        if (values.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        return ProgressSeriesEngine.filter(
            points = values.map { ProgressSeriesPoint(it.first, it.second) },
            range = range,
            zoneId = zone,
            asOfDate = time.today(),
        ).points.map { it.timestamp to it.value }
    }

    private fun rangePeriod(index: Int): Period = when (index) {
        0 -> Period.ofWeeks(1)
        1 -> Period.ofMonths(1)
        2 -> Period.ofMonths(3)
        else -> Period.ofYears(1)
    }

    private fun metricState(valuesChronological: List<Pair<Long, Float>>): MetricState {
        val current = valuesChronological.lastOrNull()?.second
        val previous = valuesChronological.getOrNull(valuesChronological.lastIndex - 1)?.second
        return MetricState(current, if (current != null && previous != null) current - previous else null)
    }

    class Factory(
        private val profiles: UserProfileRepository,
        private val biaRepository: BiaRepository,
        private val bodyRepository: BodyMeasurementRepository,
        private val mealPlanRepository: MealPlanRepository,
        private val cheatRepository: CheatEntryRepository,
        private val recoveryRepository: CalorieRecoveryRepository,
        private val foodConsumptionRepository: FoodConsumptionRepository,
        private val bodyExpectationGoalRepository: BodyExpectationGoalRepository,
        private val nutritionPlanSchedulePreferences: NutritionPlanSchedulePreferences,
        private val activeProfileStore: ActiveProfileStore,
        private val profileCalculationService: ProfileCalculationService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(profiles, biaRepository, bodyRepository, mealPlanRepository, cheatRepository, recoveryRepository, foodConsumptionRepository, bodyExpectationGoalRepository, nutritionPlanSchedulePreferences, activeProfileStore, profileCalculationService) as T
        }
    }
}
