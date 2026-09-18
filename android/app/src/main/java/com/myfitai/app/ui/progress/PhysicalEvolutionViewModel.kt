package com.myfitai.app.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.domain.progress.ProgressSeriesEngine
import com.myfitai.app.domain.progress.ProgressSeriesPoint
import com.myfitai.app.domain.calculation.WeeklyBodyExpectation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId

enum class ProgressMetric { WEIGHT, BODY_FAT, MUSCLE }

data class ProgressPoint(val timestamp: Long, val value: Float)
data class ProgressMetricState(val value: Float? = null, val delta: Float? = null, val series: List<ProgressPoint> = emptyList())
data class PhysicalEvolutionState(
    val weight: ProgressMetricState = ProgressMetricState(),
    val bodyFat: ProgressMetricState = ProgressMetricState(),
    val muscle: ProgressMetricState = ProgressMetricState(),
    val bodyWater: ProgressMetricState = ProgressMetricState(),
    val weeklyExpectation: WeeklyBodyExpectation.Result = WeeklyBodyExpectation.Result(false, WeeklyBodyExpectation.Source.INSUFFICIENT_DATA),
)

class PhysicalEvolutionViewModel(
    bia: BiaRepository,
    activeProfileStore: ActiveProfileStore,
) : ViewModel() {
    val state: StateFlow<PhysicalEvolutionState> = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
            if (profileId <= 0L) flowOf(emptyList()) else bia.all(profileId)
    }.map { rows ->
        fun metric(selector: (com.myfitai.app.data.local.entity.BiaMeasurementEntity) -> Float?): ProgressMetricState {
            val points = rows.mapNotNull { r -> selector(r)?.let { ProgressPoint(r.measuredAtEpochMillis, it) } }
                .sortedBy { it.timestamp }
            return ProgressMetricState(
                value = points.lastOrNull()?.value,
                delta = if (points.size >= 2) points.last().value - points.first().value else null,
                series = points,
            )
        }
        PhysicalEvolutionState(
            weight = metric { it.weightKg },
            bodyFat = metric { it.bodyFatPercent },
            muscle = metric { it.muscleMassKg },
            bodyWater = metric { it.bodyWaterPercent },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PhysicalEvolutionState())

    fun filtered(metric: ProgressMetricState, rangeIndex: Int): ProgressMetricState {
        val zone = ZoneId.systemDefault()
        val result = ProgressSeriesEngine.filter(
            metric.series.map { ProgressSeriesPoint(it.timestamp, it.value) },
            rangeIndex,
            zone,
        )
        return metric.copy(
            value = result.value,
            delta = result.delta,
            series = result.points.map { ProgressPoint(it.timestamp, it.value) },
        )
    }

    class Factory(private val bia: BiaRepository, private val activeProfileStore: ActiveProfileStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PhysicalEvolutionViewModel(bia, activeProfileStore) as T
    }
}
