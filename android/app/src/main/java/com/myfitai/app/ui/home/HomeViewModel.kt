package com.myfitai.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.domain.food.FoodConsumptionItemType
import com.myfitai.app.domain.food.FoodConsumptionStatus
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.EnergyTargetPresentation
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.calculation.WeeklyBodyExpectation
import com.myfitai.app.domain.food.NutritionRecoveryTargetEngine
import com.myfitai.app.data.repository.NutritionRecoveryRepository
import com.myfitai.app.domain.food.FoodMeal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class HomeViewModel(
    private val profiles: UserProfileRepository,
    private val biaRepository: BiaRepository,
    private val bodyRepository: BodyMeasurementRepository,
    private val workoutRepository: WorkoutRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val profileCalculationService: ProfileCalculationService,
    private val recoveryRepository: NutritionRecoveryRepository,
    private val foodConsumptionRepository: FoodConsumptionRepository,
) : ViewModel() {

    data class MetricState(val value: Float?, val deltaFromPrevious: Float?)
    data class TrendSeries(val label: String, val values: List<Float>)
    data class NextWorkoutState(val startedAtEpochMillis: Long, val title: String, val type: String)
    data class NextMealState(
        val mealId: Long,
        val dateEpochDay: Long,
        val timeMinutes: Int?,
        val title: String,
        val type: String,
        val kcal: Int?,
    )

    data class UpcomingMealsState(val meals: List<NextMealState> = emptyList())

    data class DashboardState(
        val profileName: String? = null,
        val goal: String? = null,
        val weight: MetricState = MetricState(null, null),
        val bodyFat: MetricState = MetricState(null, null),
        val muscleMass: MetricState = MetricState(null, null),
        val trendSeries: List<TrendSeries> = emptyList(),
        val recompositionState: LocalCalculationEngine.RecompositionState = LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA,
        val nextWorkout: NextWorkoutState? = null,
        val nextMeal: NextMealState? = null,
         val energy: EnergyTargetPresentation.State = EnergyTargetPresentation.State(mode = EnergyTargetPresentation.Mode.INSUFFICIENT_DATA),
        val plannedMealsToday: Int = 0,
         val consumedMealsToday: Int = 0,
         val consumedKcalToday: Int = 0,
        val latestBiaAgeDays: Long? = null,
        val latestBodyMeasurementAgeDays: Long? = null,
         val upcomingMeals: UpcomingMealsState = UpcomingMealsState(),
         val todayMenu: List<NextMealState> = emptyList(),
        val workoutToday: NextWorkoutState? = null,
         val restDayToday: Boolean = false,
          val weeklyExpectation: WeeklyBodyExpectation.Result = WeeklyBodyExpectation.Result(false, WeeklyBodyExpectation.Source.INSUFFICIENT_DATA),
    )

    private data class Source(
        val profile: UserProfileEntity?,
        val bia: List<BiaMeasurementEntity>,
        val body: List<BodyMeasurementEntity>,
        val workouts: List<WorkoutEntity>,
        val calculation: LocalCalculationEngine.Result?,
        val recovery: NutritionRecoveryTargetEngine.State?,
        val plannedMealsToday: Int = 0,
         val consumedMealsToday: Int = 0,
         val consumedKcalToday: Int = 0,
        val latestBiaAgeDays: Long? = null,
        val latestBodyMeasurementAgeDays: Long? = null,
         val upcomingMeals: List<NextMealState> = emptyList(),
         val todayMenu: List<NextMealState> = emptyList(),
        val workoutToday: NextWorkoutState? = null,
         val restDayToday: Boolean = false,
         val weeklyExpectation: WeeklyBodyExpectation.Result = WeeklyBodyExpectation.Result(false, WeeklyBodyExpectation.Source.INSUFFICIENT_DATA),
    )

    private data class SourceInputs(
        val profile: UserProfileEntity?,
        val bia: List<BiaMeasurementEntity>,
        val body: List<BodyMeasurementEntity>,
        val workouts: List<WorkoutEntity>,
        val recoveryEvents: List<com.myfitai.app.data.local.entity.NutritionRecoveryEventEntity>,
    )

    private val selectedRange = MutableStateFlow(1)

    private val source = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf(Source(null, emptyList(), emptyList(), emptyList(), null, null))
        } else {
            combine(
                combine(
                    profiles.profile(profileId),
                    biaRepository.all(profileId),
                    bodyRepository.all(profileId),
                    workoutRepository.all(profileId),
                    recoveryRepository.events(profileId),
                ) { profile, bia, body, workouts, recoveryEvents -> SourceInputs(profile, bia, body, workouts, recoveryEvents) },
                mealPlanRepository.plans(profileId),
                foodConsumptionRepository.all(profileId),
            ) { inputs, _, consumptions ->
                val profile = inputs.profile
                val bia = inputs.bia
                val body = inputs.body
                val workouts = inputs.workouts
                val recoveryEvents = inputs.recoveryEvents
                val calculation = profileCalculationService.activeProfileSnapshot()?.calculation
                val recovery = calculation?.targetKcal?.toInt()?.let { target ->
                    val day = LocalDate.now().toEpochDay()
                    NutritionRecoveryTargetEngine.calculate(
                        target,
                        recoveryEvents,
                        day,
                        recoveryRepository.withdrawal(profileId, day),
                        recoveryRepository.plannedBefore(profileId, day),
                    )
                }
                val today = LocalDate.now()
                val sortedBia = bia.sortedBy { it.measuredAtEpochMillis }
                val sortedBody = body.sortedBy { it.measuredAtEpochMillis }
                val observed = WeeklyBodyExpectation.ObservedProgress(
                    weightDeltaKg = sortedBia.takeIf { it.size >= 2 }?.let { (it.last().weightKg?.toDouble() ?: return@let null) - (it.first().weightKg?.toDouble() ?: return@let null) },
                    bodyFatDeltaPercentagePoints = sortedBia.takeIf { it.size >= 2 }?.let { (it.last().bodyFatPercent?.toDouble() ?: return@let null) - (it.first().bodyFatPercent?.toDouble() ?: return@let null) },
                    muscleDeltaKg = sortedBia.takeIf { it.size >= 2 }?.let { (it.last().muscleMassKg?.toDouble() ?: return@let null) - (it.first().muscleMassKg?.toDouble() ?: return@let null) },
                    waistDeltaCm = sortedBody.takeIf { it.size >= 2 }?.let { (it.last().waistCm?.toDouble() ?: return@let null) - (it.first().waistCm?.toDouble() ?: return@let null) },
                )
                val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val plannedDays = mealPlanRepository.loadLatestSnapshot(profileId, week.toEpochDay())?.version?.days.orEmpty()
                val planExpectation = calculation?.let { calc ->
                    WeeklyBodyExpectation.calculate(
                        maintenanceKcalByDay = plannedDays.map { calc.baseTdeeKcal?.toInt() },
                        plannedFoodKcalByDay = plannedDays.map { it.totalKcal },
                        exerciseKcalByDay = plannedDays.map { 0 },
                        consumedFoodKcalByDay = consumptions.takeIf { rows -> rows.any { it.status == FoodConsumptionStatus.CONSUMED.name } }
                            ?.let { rows -> plannedDays.map { day -> rows.filter { it.plannedDateEpochDay == day.dateEpochDay && it.status == FoodConsumptionStatus.CONSUMED.name }.sumOf { it.kcal ?: 0 }.takeIf { total -> total > 0 } } },
                    )
                } ?: WeeklyBodyExpectation.Result(false, WeeklyBodyExpectation.Source.INSUFFICIENT_DATA)
                val snapshot = mealPlanRepository.loadLatestSnapshot(profileId, week.toEpochDay())
                val todayDay = snapshot?.version?.days?.firstOrNull { it.dateEpochDay == today.toEpochDay() }
                val planned = todayDay?.meals?.size ?: 0
                val consumed = consumptions.count { it.plannedDateEpochDay == today.toEpochDay() && it.itemType == FoodConsumptionItemType.MEAL.name && it.status == FoodConsumptionStatus.CONSUMED.name }
                val consumedKcal = consumptions.filter { it.plannedDateEpochDay == today.toEpochDay() && it.status == FoodConsumptionStatus.CONSUMED.name }.sumOf { it.kcal ?: 0 }
                val nowMinutes = LocalTime.now().hour * 60 + LocalTime.now().minute
                val upcoming = todayDay?.meals.orEmpty().sortedBy { it.timeMinutes ?: Int.MAX_VALUE }.filter { it.timeMinutes == null || it.timeMinutes >= nowMinutes }.take(2).map {
                    NextMealState(it.id, today.toEpochDay(), it.timeMinutes, it.title, it.type, it.kcal)
                }
                val todayMenu = todayDay?.meals.orEmpty().sortedBy { it.timeMinutes ?: Int.MAX_VALUE }.map {
                    NextMealState(it.id, today.toEpochDay(), it.timeMinutes, it.title, it.type, it.kcal)
                }
                val todayEpoch = today.toEpochDay()
                val biaAge = bia.maxOfOrNull { it.measuredAtEpochMillis }?.let { todayEpoch - Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() }
                val bodyAge = body.maxOfOrNull { it.measuredAtEpochMillis }?.let { todayEpoch - Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() }
                  Source(profile, bia, body, workouts, calculation, recovery, plannedMealsToday = planned, consumedMealsToday = consumed, consumedKcalToday = consumedKcal, latestBiaAgeDays = biaAge, latestBodyMeasurementAgeDays = bodyAge, upcomingMeals = upcoming, todayMenu = todayMenu, weeklyExpectation = planExpectation.copy(goalStatus = WeeklyBodyExpectation.assessGoal(ProfileCalculationMapper.goal(profile?.goal), observed), observedWeightDeltaKg = observed.weightDeltaKg, observedFatDeltaPercentagePoints = observed.bodyFatDeltaPercentagePoints, observedMuscleDeltaKg = observed.muscleDeltaKg, observedWaistDeltaCm = observed.waistDeltaCm))
            }
        }
    }

    private val nextMealSource = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf<NextMealState?>(null)
        } else {
            mealPlanRepository.plans(profileId).flatMapLatest {
                flow { emit(findNextMeal(profileId)) }
            }
        }
    }

    val state: StateFlow<DashboardState> = combine(source, selectedRange, nextMealSource) { source, rangeIndex, nextMeal ->
        buildState(source, rangeIndex, nextMeal)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardState())

    fun selectRange(index: Int) { selectedRange.value = index.coerceIn(0, 3) }

    private fun buildState(source: Source, rangeIndex: Int, nextMeal: NextMealState?): DashboardState {
        val weightValues = metricValues(source.bia) { it.weightKg }
            .ifEmpty { source.profile?.currentWeightKg?.let { listOf(System.currentTimeMillis() to it) }.orEmpty() }
        val fatValues = metricValues(source.bia) { it.bodyFatPercent }
        val muscleValues = metricValues(source.bia) { it.muscleMassKg }
        val fatTrend = LocalCalculationEngine.trend(fatValues.map { LocalCalculationEngine.TimedValue(it.first, it.second.toDouble()) })
        val muscleTrend = LocalCalculationEngine.trend(muscleValues.map { LocalCalculationEngine.TimedValue(it.first, it.second.toDouble()) })
        val weightSeries = filterRange(weightValues, rangeIndex).map { it.second }
        val fatSeries = filterRange(fatValues, rangeIndex).map { it.second }
        val muscleSeries = filterRange(muscleValues, rangeIndex).map { it.second }
        val now = System.currentTimeMillis()
        val nextWorkout = source.workouts
            .asSequence()
            .filter { !it.isRestDay && it.startedAtEpochMillis >= now }
            .minWithOrNull(compareBy<WorkoutEntity> { it.startedAtEpochMillis }.thenBy { it.id })
            ?.let { NextWorkoutState(it.startedAtEpochMillis, it.title, it.type) }
        val today = LocalDate.now()
        val todayWorkout = source.workouts.firstOrNull { Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate() == today }

        return DashboardState(
            profileName = source.profile?.name,
            goal = source.profile?.goal,
            weight = metricState(weightValues),
            bodyFat = metricState(fatValues),
            muscleMass = metricState(muscleValues),
            trendSeries = listOf(
                TrendSeries("Peso", weightSeries),
                TrendSeries("Grasso corporeo", fatSeries),
                TrendSeries("Massa muscolare", muscleSeries),
            ),
            recompositionState = LocalCalculationEngine.classifyRecomposition(fatTrend.delta, muscleTrend.delta),
            nextWorkout = nextWorkout,
            nextMeal = nextMeal,
             energy = EnergyTargetPresentation.build(
                 calculation = source.calculation,
                 recovery = source.recovery,
                 goal = ProfileCalculationMapper.goal(source.profile?.goal),
             ),
             plannedMealsToday = source.plannedMealsToday,
              consumedMealsToday = source.consumedMealsToday,
              consumedKcalToday = source.consumedKcalToday,
             latestBiaAgeDays = source.latestBiaAgeDays,
             latestBodyMeasurementAgeDays = source.latestBodyMeasurementAgeDays,
              upcomingMeals = UpcomingMealsState(source.upcomingMeals),
              todayMenu = source.todayMenu,
             workoutToday = todayWorkout?.takeIf { !it.isRestDay }?.let { NextWorkoutState(it.startedAtEpochMillis, it.title, it.type) },
             restDayToday = todayWorkout?.isRestDay == true,
             weeklyExpectation = source.weeklyExpectation,
        )
    }

    private suspend fun findNextMeal(profileId: Long): NextMealState? {
        val today = LocalDate.now()
        val nowMinutes = LocalTime.now().hour * 60 + LocalTime.now().minute
        val currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val snapshots = listOfNotNull(
            mealPlanRepository.loadLatestSnapshot(profileId, currentWeek.toEpochDay()),
            mealPlanRepository.loadLatestSnapshot(profileId, currentWeek.plusWeeks(1).toEpochDay()),
        )

        for (snapshot in snapshots) {
            for (day in snapshot.version.days.sortedBy { it.dateEpochDay }) {
                val date = LocalDate.ofEpochDay(day.dateEpochDay)
                if (date.isBefore(today)) continue
                val meals = day.meals.sortedWith(compareBy<FoodMeal> { it.timeMinutes ?: Int.MAX_VALUE }.thenBy { it.sortOrder })
                val meal = meals.firstOrNull { candidate ->
                    date.isAfter(today) || candidate.timeMinutes == null || candidate.timeMinutes >= nowMinutes
                } ?: continue
                return NextMealState(
                    mealId = meal.id,
                    dateEpochDay = day.dateEpochDay,
                    timeMinutes = meal.timeMinutes,
                    title = meal.title,
                    type = meal.type,
                    kcal = meal.kcal,
                )
            }
        }
        return null
    }

    private fun metricState(valuesDesc: List<Pair<Long, Float>>): MetricState {
        val current = valuesDesc.getOrNull(0)?.second
        val previous = valuesDesc.getOrNull(1)?.second
        return MetricState(current, if (current != null && previous != null) current - previous else null)
    }

    private fun metricValues(history: List<BiaMeasurementEntity>, selector: (BiaMeasurementEntity) -> Float?): List<Pair<Long, Float>> =
        history.mapNotNull { row -> selector(row)?.let { row.measuredAtEpochMillis to it } }

    private fun filterRange(valuesDesc: List<Pair<Long, Float>>, rangeIndex: Int): List<Pair<Long, Float>> {
        if (valuesDesc.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val anchor = Instant.ofEpochMilli(valuesDesc.first().first).atZone(zone).toLocalDate()
        val from = when (rangeIndex) {
            0 -> anchor.minusWeeks(1)
            1 -> anchor.minusMonths(1)
            2 -> anchor.minusMonths(3)
            else -> anchor.minusYears(1)
        }
        return valuesDesc.filter { (timestamp, _) -> !Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().isBefore(from) }.asReversed()
    }

    class Factory(
        private val profiles: UserProfileRepository,
        private val biaRepository: BiaRepository,
        private val bodyRepository: BodyMeasurementRepository,
        private val workoutRepository: WorkoutRepository,
        private val mealPlanRepository: MealPlanRepository,
        private val activeProfileStore: ActiveProfileStore,
        private val profileCalculationService: ProfileCalculationService,
        private val recoveryRepository: NutritionRecoveryRepository,
        private val foodConsumptionRepository: FoodConsumptionRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(profiles, biaRepository, bodyRepository, workoutRepository, mealPlanRepository, activeProfileStore, profileCalculationService, recoveryRepository, foodConsumptionRepository) as T
        }
    }
}
