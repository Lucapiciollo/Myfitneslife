package com.myfitai.app.domain.calculation

import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.TimeUnit

/**
 * Compone profilo + storici BIA + misure corporee in uno snapshot locale pronto per UI/AI.
 * Nessuna Activity deve ricostruire questi calcoli autonomamente.
 */
class ProfileCalculationService(
    private val profiles: UserProfileRepository,
    private val bia: BiaRepository,
    private val bodyMeasurements: BodyMeasurementRepository,
    private val activeProfileStore: ActiveProfileStore,
) {

    data class MetricSnapshot(
        val baseline: Float?,
        val current: Float?,
        val previousDelta: Float?,
        val recentTrend: LocalCalculationEngine.TrendStats,
        val recentSpanDays: Int,
    )

    data class BiaSnapshot(
        val weight: MetricSnapshot,
        val bodyFat: MetricSnapshot,
        val muscleMass: MetricSnapshot,
        val skeletalMuscle: MetricSnapshot,
        val bodyWater: MetricSnapshot,
        val visceralFat: MetricSnapshot,
    )

    data class BodyMeasurementsSnapshot(
        val chest: MetricSnapshot,
        val waist: MetricSnapshot,
        val abdomen: MetricSnapshot,
        val shoulders: MetricSnapshot,
        val glutes: MetricSnapshot,
        val armLeft: MetricSnapshot,
        val armRight: MetricSnapshot,
        val thighLeft: MetricSnapshot,
        val thighRight: MetricSnapshot,
        val calfLeft: MetricSnapshot,
        val calfRight: MetricSnapshot,
    )

    data class Snapshot(
        val profileId: Long,
        val calculation: LocalCalculationEngine.Result,
        val weightTrend: LocalCalculationEngine.TrendStats,
        val bodyFatTrend: LocalCalculationEngine.TrendStats,
        val muscleMassTrend: LocalCalculationEngine.TrendStats,
        val waistTrend: LocalCalculationEngine.TrendStats,
        val recompositionState: LocalCalculationEngine.RecompositionState,
        val latestBiaTimestamp: Long?,
        val latestBodyMeasurementTimestamp: Long?,
        val latestWeightKg: Float?,
        val latestBodyFatPercent: Float?,
        val latestMuscleMassKg: Float?,
        val latestSkeletalMuscleKg: Float?,
        val latestBodyWaterPercent: Float?,
        val latestWaistCm: Float?,
        val biaMetrics: BiaSnapshot,
        val bodyMetrics: BodyMeasurementsSnapshot,
    )

    suspend fun activeProfileSnapshot(today: LocalDate = LocalDate.now()): Snapshot? {
        val profileId = activeProfileStore.currentIdOrNull() ?: return null
        return profileSnapshot(profileId, today)
    }

    /** Used by background work so analysis remains bound to the profile that was scheduled. */
    suspend fun profileSnapshot(profileId: Long, today: LocalDate = LocalDate.now()): Snapshot? {
        val profile = profiles.get(profileId) ?: return null
        val biaHistory = bia.all(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val bodyHistory = bodyMeasurements.all(profileId).first().sortedBy { it.measuredAtEpochMillis }

        val latestBia = biaHistory.lastOrNull()
        val latestBody = bodyHistory.lastOrNull()
        val weightObservations = (
            biaHistory.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } } +
                bodyHistory.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } }
            ).sortedBy { it.first }
        val latestRecordedWeight = weightObservations.lastOrNull()?.second
        val weightKg = latestRecordedWeight?.toDouble() ?: profile.currentWeightKg?.toDouble()
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

        val weightTrend = trendOf(weightObservations.map { it.first to it.second.toDouble() })
        val bodyFatTrend = trendOf(biaHistory.mapNotNull { row -> row.bodyFatPercent?.let { row.measuredAtEpochMillis to it.toDouble() } })
        val muscleTrend = trendOf(biaHistory.mapNotNull { row -> row.muscleMassKg?.let { row.measuredAtEpochMillis to it.toDouble() } })
        val waistTrend = trendOf(bodyHistory.mapNotNull { row -> row.waistCm?.let { row.measuredAtEpochMillis to it.toDouble() } })
        val recomposition = LocalCalculationEngine.classifyRecomposition(bodyFatTrend.delta, muscleTrend.delta)

        val biaMetrics = BiaSnapshot(
            weight = metricSnapshot(biaHistory.map { it.measuredAtEpochMillis to it.weightKg }),
            bodyFat = metricSnapshot(biaHistory.map { it.measuredAtEpochMillis to it.bodyFatPercent }),
            muscleMass = metricSnapshot(biaHistory.map { it.measuredAtEpochMillis to it.muscleMassKg }),
            skeletalMuscle = metricSnapshot(biaHistory.map { it.measuredAtEpochMillis to it.skeletalMuscleKg }),
            bodyWater = metricSnapshot(biaHistory.map { it.measuredAtEpochMillis to it.bodyWaterPercent }),
            visceralFat = metricSnapshot(biaHistory.map { it.measuredAtEpochMillis to it.visceralFatLevel }),
        )
        val bodyMetrics = BodyMeasurementsSnapshot(
            chest = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.chestCm }),
            waist = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.waistCm }),
            abdomen = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.abdomenCm }),
            shoulders = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.shouldersCm }),
            glutes = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.glutesCm }),
            armLeft = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.armLeftCm }),
            armRight = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.armRightCm }),
            thighLeft = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.thighLeftCm }),
            thighRight = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.thighRightCm }),
            calfLeft = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.calfLeftCm }),
            calfRight = metricSnapshot(bodyHistory.map { it.measuredAtEpochMillis to it.calfRightCm }),
        )

        return Snapshot(
            profileId = profileId,
            calculation = calculation,
            weightTrend = weightTrend,
            bodyFatTrend = bodyFatTrend,
            muscleMassTrend = muscleTrend,
            waistTrend = waistTrend,
            recompositionState = recomposition,
            latestBiaTimestamp = latestBia?.measuredAtEpochMillis,
            latestBodyMeasurementTimestamp = latestBody?.measuredAtEpochMillis,
            latestWeightKg = latestRecordedWeight ?: profile.currentWeightKg,
            latestBodyFatPercent = latestBia?.bodyFatPercent,
            latestMuscleMassKg = latestBia?.muscleMassKg,
            latestSkeletalMuscleKg = latestBia?.skeletalMuscleKg,
            latestBodyWaterPercent = latestBia?.bodyWaterPercent,
            latestWaistCm = latestBody?.waistCm,
            biaMetrics = biaMetrics,
            bodyMetrics = bodyMetrics,
        )
    }

    private fun metricSnapshot(values: List<Pair<Long, Float?>>): MetricSnapshot {
        val ordered = values.mapNotNull { (timestamp, value) -> value?.let { timestamp to it } }.sortedBy { it.first }
        val baseline = ordered.firstOrNull()?.second
        val current = ordered.lastOrNull()?.second
        val previousDelta = if (ordered.size >= 2) ordered.last().second - ordered[ordered.lastIndex - 1].second else null
        val anchor = ordered.lastOrNull()?.first
        val recent = if (anchor == null) emptyList() else ordered.filter { (timestamp, _) ->
            timestamp >= anchor - TimeUnit.DAYS.toMillis(RECENT_WINDOW_DAYS.toLong())
        }
        val recentSpanDays = if (recent.size >= 2) {
            TimeUnit.MILLISECONDS.toDays(recent.last().first - recent.first().first).toInt()
        } else 0
        val recentTrend = trendOf(recent.map { (timestamp, value) -> timestamp to value.toDouble() })
        return MetricSnapshot(baseline, current, previousDelta, recentTrend, recentSpanDays)
    }

    private fun trendOf(values: List<Pair<Long, Double>>): LocalCalculationEngine.TrendStats =
        LocalCalculationEngine.trend(values.map { (timestamp, value) -> LocalCalculationEngine.TimedValue(timestamp, value) })

    companion object {
        const val RECENT_WINDOW_DAYS = 56
    }
}
