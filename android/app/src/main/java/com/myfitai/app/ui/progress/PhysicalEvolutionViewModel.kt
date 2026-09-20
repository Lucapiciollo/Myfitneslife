package com.myfitai.app.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.domain.progress.ProgressSeriesEngine
import com.myfitai.app.domain.progress.ProgressSeriesPoint
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
)

class PhysicalEvolutionViewModel(
    bia: BiaRepository,
    bodyMeasurements: BodyMeasurementRepository,
    activeProfileStore: ActiveProfileStore,
) : ViewModel() {
    val state: StateFlow<PhysicalEvolutionState> = activeProfileStore.activeProfileId.flatMapLatest { profileId ->
        if (profileId <= 0L) {
            kotlinx.coroutines.flow.flowOf(emptyList<com.myfitai.app.data.local.entity.BiaMeasurementEntity>() to emptyList<com.myfitai.app.data.local.entity.BodyMeasurementEntity>())
        } else {
            kotlinx.coroutines.flow.combine(bia.all(profileId), bodyMeasurements.all(profileId)) { biaRows, bodyRows -> biaRows to bodyRows }
        }
    }.map { (biaRows, bodyRows) ->
        fun metric(selector: (com.myfitai.app.data.local.entity.BiaMeasurementEntity) -> Float?): ProgressMetricState {
            val points = biaRows.mapNotNull { row -> selector(row)?.let { ProgressPoint(row.measuredAtEpochMillis, it) } }
                .sortedBy { it.timestamp }
            return ProgressMetricState(
                value = points.lastOrNull()?.value,
                delta = if (points.size >= 2) points.last().value - points.first().value else null,
                series = points,
            )
        }
        val weightPoints = (biaRows.mapNotNull { row -> row.weightKg?.let { ProgressPoint(row.measuredAtEpochMillis, it) } } +
            bodyRows.mapNotNull { row -> row.weightKg?.let { ProgressPoint(row.measuredAtEpochMillis, it) } })
            .distinctBy { it.timestamp to it.value }
            .sortedBy { it.timestamp }
        PhysicalEvolutionState(
            weight = metric { it.weightKg }.copy(
                value = weightPoints.lastOrNull()?.value,
                delta = if (weightPoints.size >= 2) weightPoints.last().value - weightPoints.first().value else null,
                series = weightPoints,
            ),
            bodyFat = metric { it.bodyFatPercent },
            muscle = metric { it.muscleMassKg },
            bodyWater = metric { it.bodyWaterPercent },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhysicalEvolutionState())

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

    class Factory(
        private val bia: BiaRepository,
        private val bodyMeasurements: BodyMeasurementRepository,
        private val activeProfileStore: ActiveProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PhysicalEvolutionViewModel(bia, bodyMeasurements, activeProfileStore) as T
    }
}
