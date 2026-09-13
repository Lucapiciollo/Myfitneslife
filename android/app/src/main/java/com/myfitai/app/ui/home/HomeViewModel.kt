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
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId

class HomeViewModel(
    private val profiles: UserProfileRepository,
    private val biaRepository: BiaRepository,
    private val bodyRepository: BodyMeasurementRepository,
    private val workoutRepository: WorkoutRepository,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {

    data class MetricState(val value: Float?, val deltaFromPrevious: Float?)
    data class NextWorkoutState(val startedAtEpochMillis: Long, val title: String, val type: String)

    data class DashboardState(
        val profileName: String? = null,
        val goal: String? = null,
        val weight: MetricState = MetricState(null, null),
        val bodyFat: MetricState = MetricState(null, null),
        val muscleMass: MetricState = MetricState(null, null),
        val weightSeries: List<Float> = emptyList(),
        val recompositionState: LocalCalculationEngine.RecompositionState = LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA,
        val nextWorkout: NextWorkoutState? = null,
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

    val state: StateFlow<DashboardState> = combine(source, selectedRange) { source, rangeIndex ->
        buildState(source, rangeIndex)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun selectRange(index: Int) { selectedRange.value = index.coerceIn(0, 3) }

    private fun buildState(source: Source, rangeIndex: Int): DashboardState {
        val weightValues = metricValues(source.bia) { it.weightKg }
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
        )
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
        private val activeProfileStore: ActiveProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(profiles, biaRepository, bodyRepository, workoutRepository, activeProfileStore) as T
        }
    }
}
