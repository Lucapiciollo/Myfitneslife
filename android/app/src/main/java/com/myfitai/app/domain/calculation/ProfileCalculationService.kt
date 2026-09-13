package com.myfitai.app.domain.calculation

import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.Period

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
    )

    suspend fun activeProfileSnapshot(today: LocalDate = LocalDate.now()): Snapshot? {
        val profileId = activeProfileStore.currentIdOrNull() ?: return null
        val profile = profiles.get(profileId) ?: return null
        val biaHistory = bia.all(profileId).first()
        val bodyHistory = bodyMeasurements.all(profileId).first()

        val latestBia = biaHistory.firstOrNull()
        val latestBody = bodyHistory.firstOrNull()
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
                biologicalSex = null,
                bodyFatPercent = latestBia?.bodyFatPercent?.toDouble(),
                activityLevel = ProfileCalculationMapper.activity(profile.activityLevel),
                goal = ProfileCalculationMapper.goal(profile.goal),
                waistCm = latestBody?.waistCm?.toDouble(),
            )
        )

        val weightTrend = trendOf(biaHistory.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it.toDouble() } })
        val bodyFatTrend = trendOf(biaHistory.mapNotNull { row -> row.bodyFatPercent?.let { row.measuredAtEpochMillis to it.toDouble() } })
        val muscleTrend = trendOf(biaHistory.mapNotNull { row -> row.muscleMassKg?.let { row.measuredAtEpochMillis to it.toDouble() } })
        val waistTrend = trendOf(bodyHistory.mapNotNull { row -> row.waistCm?.let { row.measuredAtEpochMillis to it.toDouble() } })

        val bodyFatDelta = bodyFatTrend.delta
        val muscleDelta = muscleTrend.delta
        val recomposition = LocalCalculationEngine.classifyRecomposition(bodyFatDelta, muscleDelta)

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
        )
    }

    private fun trendOf(values: List<Pair<Long, Double>>): LocalCalculationEngine.TrendStats =
        LocalCalculationEngine.trend(values.map { (timestamp, value) -> LocalCalculationEngine.TimedValue(timestamp, value) })
}
