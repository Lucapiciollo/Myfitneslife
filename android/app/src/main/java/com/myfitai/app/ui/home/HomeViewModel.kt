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
import com.myfitai.app.data.repository.CalorieRecoveryRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import com.myfitai.app.domain.calculation.WeeklyBodyExpectation
import com.myfitai.app.domain.food.CalorieRecoveryEngine
import com.myfitai.app.domain.food.FoodConsumptionMetrics
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class HomeViewModel(
    private val profiles: UserProfileRepository,
    private val biaRepository: BiaRepository,
    private val bodyRepository: BodyMeasurementRepository,
    private val workoutRepository: WorkoutRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val cheatRepository: CheatEntryRepository,
    private val recoveryRepository: CalorieRecoveryRepository,
    private val foodConsumptionRepository: FoodConsumptionRepository,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {

    data class MetricState(val value: Float?, val deltaFromPrevious: Float?)
    data class TrendSeries(val label: String, val values: List<Float>)
    data class CalorieState(
        val bmr: Int? = null,
        val tdee: Int? = null,
        val target: Int? = null,
        val goalLabel: String? = null,
        val energyPercent: Int? = null,
        val consumedKcal: Int = 0,
    )
    data class NextWorkoutState(val startedAtEpochMillis: Long, val title: String, val type: String)
    data class NextMealState(
        val mealId: Long,
        val dateEpochDay: Long,
        val timeMinutes: Int?,
        val title: String,
        val type: String,
        val kcal: Int?,
    )

    data class RecoveryState(val pendingKcal: Int = 0, val creditCount: Int = 0)

    data class DashboardState(
        val profileName: String? = null,
        val goal: String? = null,
        val weight: MetricState = MetricState(null, null),
        val bodyFat: MetricState = MetricState(null, null),
        val muscleMass: MetricState = MetricState(null, null),
        val trendSeries: List<TrendSeries> = emptyList(),
        val recompositionState: LocalCalculationEngine.RecompositionState = LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA,
        val calories: CalorieState = CalorieState(),
        val nextWorkout: NextWorkoutState? = null,
        val nextMeal: NextMealState? = null,
        val upcomingMeals: List<NextMealState> = emptyList(),
        val recovery: RecoveryState = RecoveryState(),
        val weeklyExpectation: WeeklyBodyExpectation.Result = WeeklyBodyExpectation.Result(available = false),
    )

    private data class Source(
        val profile: UserProfileEntity?,
        val bia: List<BiaMeasurementEntity>,
        val body: List<BodyMeasurementEntity>,
        val workouts: List<WorkoutEntity>,
    )

    private val selectedRange = MutableStateFlow(1)

    private val source = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf(Source(null, emptyList(), emptyList(), emptyList()))
        } else {
            combine(
                profiles.profile(profileId),
                biaRepository.all(profileId),
                bodyRepository.all(profileId),
                workoutRepository.all(profileId),
            ) { profile, bia, body, workouts -> Source(profile, bia, body, workouts) }
        }
    }

    private val upcomingMealsSource = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf<List<NextMealState>>(emptyList())
        } else {
            mealPlanRepository.plans(profileId).flatMapLatest {
                flow { emit(findUpcomingMeals(profileId, limit = 2)) }
            }
        }
    }

    private val recoverySource = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf(RecoveryState())
        } else {
            val zone = ZoneId.systemDefault()
            val windowStart = LocalDate.now().minusDays(CalorieRecoveryEngine.WINDOW_DAYS)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            cheatRepository.between(profileId, windowStart, now).map { entries ->
                computeRecovery(profileId, entries)
            }
        }
    }

    private val consumedTodaySource = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf(0)
        } else {
            foodConsumptionRepository.forDay(profileId, LocalDate.now().toEpochDay()).map { records ->
                Math.round(FoodConsumptionMetrics.dayTotals(records).kcal).toInt()
            }
        }
    }

    private val weeklyPlanSource = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            flowOf<List<FoodPlanDay>>(emptyList())
        } else {
            mealPlanRepository.plans(profileId).flatMapLatest {
                flow {
                    val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    emit(mealPlanRepository.loadLatestSnapshot(profileId, monday.toEpochDay())?.version?.days.orEmpty())
                }
            }
        }
    }

    val state: StateFlow<DashboardState> = combine(
        source,
        selectedRange,
        upcomingMealsSource,
        recoverySource,
        consumedTodaySource,
    ) { source, rangeIndex, upcomingMeals, recovery, consumedKcal ->
        buildState(source, rangeIndex, upcomingMeals, recovery, consumedKcal)
    }.combine(weeklyPlanSource) { dashboard, plannedDays ->
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        dashboard.copy(
            weeklyExpectation = WeeklyBodyExpectation.calculate(
                maintenanceKcal = dashboard.calories.tdee,
                weekStartEpochDay = monday.toEpochDay(),
                plannedDays = plannedDays.map { it.dateEpochDay to it.totalKcal },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun selectRange(index: Int) { selectedRange.value = index.coerceIn(0, 3) }

    private fun buildState(
        source: Source,
        rangeIndex: Int,
        upcomingMeals: List<NextMealState>,
        recovery: RecoveryState,
        consumedKcal: Int,
    ): DashboardState {
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

        val calories = calorieState(source).copy(consumedKcal = consumedKcal)

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
            calories = calories,
            nextWorkout = nextWorkout,
            nextMeal = upcomingMeals.firstOrNull(),
            upcomingMeals = upcomingMeals,
            recovery = recovery,
        )
    }

    private fun calorieState(source: Source): CalorieState {
        val profile = source.profile ?: return CalorieState()
        val today = LocalDate.now()
        val latestBia = source.bia.maxByOrNull { it.measuredAtEpochMillis }
        val latestBody = source.body.maxByOrNull { it.measuredAtEpochMillis }
        val weightKg = latestBia?.weightKg?.toDouble() ?: profile.currentWeightKg?.toDouble()
        val ageYears = profile.birthDateEpochDay?.let { epochDay ->
            val birth = LocalDate.ofEpochDay(epochDay)
            if (birth.isAfter(today)) null else Period.between(birth, today).years
        }
        val calculation = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = weightKg,
                heightCm = profile.heightCm?.toDouble(),
                ageYears = ageYears,
                biologicalSex = when (profile.biologicalSex?.trim()?.lowercase()) {
                    "maschio", "male", "m" -> LocalCalculationEngine.BiologicalSex.MALE
                    "femmina", "female", "f" -> LocalCalculationEngine.BiologicalSex.FEMALE
                    else -> null
                },
                bodyFatPercent = latestBia?.bodyFatPercent?.toDouble(),
                activityLevel = ProfileCalculationMapper.activity(profile.activityLevel),
                goal = ProfileCalculationMapper.goal(profile.goal),
                waistCm = latestBody?.waistCm?.toDouble(),
            )
        )
        val tdeeInt = calculation.tdeeKcal?.let { Math.round(it).toInt() }
        val targetInt = calculation.targetKcal?.let { Math.round(it).toInt() }
        val energyPercent = if (tdeeInt != null && targetInt != null && tdeeInt > 0) {
            Math.round((targetInt - tdeeInt) * 100.0 / tdeeInt).toInt()
        } else {
            null
        }
        return CalorieState(
            bmr = calculation.bmrKcal?.let { Math.round(it).toInt() },
            tdee = tdeeInt,
            target = targetInt,
            goalLabel = goalLabel(ProfileCalculationMapper.goal(profile.goal)),
            energyPercent = energyPercent,
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

    private suspend fun findUpcomingMeals(profileId: Long, limit: Int): List<NextMealState> {
        val today = LocalDate.now()
        val nowMinutes = LocalTime.now().hour * 60 + LocalTime.now().minute
        val currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val snapshots = listOfNotNull(
            mealPlanRepository.loadLatestSnapshot(profileId, currentWeek.toEpochDay()),
            mealPlanRepository.loadLatestSnapshot(profileId, currentWeek.plusWeeks(1).toEpochDay()),
        )

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
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var pending = 0
        var credits = 0
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
            }
        }
        return RecoveryState(pendingKcal = pending, creditCount = credits)
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
        private val cheatRepository: CheatEntryRepository,
        private val recoveryRepository: CalorieRecoveryRepository,
        private val foodConsumptionRepository: FoodConsumptionRepository,
        private val activeProfileStore: ActiveProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(profiles, biaRepository, bodyRepository, workoutRepository, mealPlanRepository, cheatRepository, recoveryRepository, foodConsumptionRepository, activeProfileStore) as T
        }
    }
}
