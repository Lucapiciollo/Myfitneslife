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
import com.myfitai.app.domain.calculation.LocalCalculationEngine
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
) : ViewModel() {

    data class MetricState(val value: Float?, val deltaFromPrevious: Float?)
    data class NextWorkoutState(val startedAtEpochMillis: Long, val title: String, val type: String)
    data class NextMealState(
        val mealId: Long,
        val dateEpochDay: Long,
        val timeMinutes: Int?,
        val title: String,
        val type: String,
        val kcal: Int?,
    )

    data class DashboardState(
        val profileName: String? = null,
        val goal: String? = null,
        val weight: MetricState = MetricState(null, null),
        val bodyFat: MetricState = MetricState(null, null),
        val muscleMass: MetricState = MetricState(null, null),
        val weightSeries: List<Float> = emptyList(),
        val recompositionState: LocalCalculationEngine.RecompositionState = LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA,
        val nextWorkout: NextWorkoutState? = null,
        val nextMeal: NextMealState? = null,
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun selectRange(index: Int) { selectedRange.value = index.coerceIn(0, 3) }

    private fun buildState(source: Source, rangeIndex: Int, nextMeal: NextMealState?): DashboardState {
        val weightValues = metricValues(source.bia) { it.weightKg }
            .ifEmpty { source.profile?.currentWeightKg?.let { listOf(System.currentTimeMillis() to it) }.orEmpty() }
        val fatValues = metricValues(source.bia) { it.bodyFatPercent }
        val muscleValues = metricValues(source.bia) { it.muscleMassKg }
        val fatTrend = LocalCalculationEngine.trend(fatValues.map { LocalCalculationEngine.TimedValue(it.first, it.second.toDouble()) })
        val muscleTrend = LocalCalculationEngine.trend(muscleValues.map { LocalCalculationEngine.TimedValue(it.first, it.second.toDouble()) })
        val now = System.currentTimeMillis()
        val nextWorkout = source.workouts
            .asSequence()
            .filter { !it.isRestDay && it.startedAtEpochMillis >= now }
            .minWithOrNull(compareBy<WorkoutEntity> { it.startedAtEpochMillis }.thenBy { it.id })
            ?.let { NextWorkoutState(it.startedAtEpochMillis, it.title, it.type) }

        return DashboardState(
            profileName = source.profile?.name,
            goal = source.profile?.goal,
            weight = metricState(weightValues),
            bodyFat = metricState(fatValues),
            muscleMass = metricState(muscleValues),
            weightSeries = filterRange(weightValues, rangeIndex).map { it.second },
            recompositionState = LocalCalculationEngine.classifyRecomposition(fatTrend.delta, muscleTrend.delta),
            nextWorkout = nextWorkout,
            nextMeal = nextMeal,
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(profiles, biaRepository, bodyRepository, workoutRepository, mealPlanRepository, activeProfileStore) as T
        }
    }
}
