package com.myfitai.app.domain.personalization

import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.data.repository.BodyExpectationGoalRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/** Reads only the active profile's history and builds a bounded local summary. */
class PersonalResponseService(
    private val activeProfileStore: ActiveProfileStore,
    private val plans: MealPlanRepository,
    private val cheats: CheatEntryRepository,
    private val workouts: WorkoutRepository,
    private val bia: BiaRepository,
    private val bodyMeasurements: BodyMeasurementRepository,
    private val expectationGoals: BodyExpectationGoalRepository,
) {
    suspend fun summarizeActiveProfile(
        nowEpochMillis: Long = System.currentTimeMillis(),
        lookbackDays: Int = 56,
    ): PersonalResponseEngine.Summary? {
        require(lookbackDays in 7..365)
        val profileId = activeProfileStore.currentIdOrNull() ?: return null
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val minWeekEpochDay = today.minusDays(lookbackDays.toLong() + 7L).toEpochDay()
        val maxWeekEpochDay = today.toEpochDay()
        val planRows = plans.plans(profileId).first()
            .filter { it.weekStartEpochDay in minWeekEpochDay..maxWeekEpochDay }

        val snapshots = planRows.mapNotNull { row ->
            plans.loadLatestSnapshot(profileId, row.weekStartEpochDay)
        }
        val versionReasons = planRows.flatMap { row ->
            plans.versions(profileId, row.id).first().map { it.reason }
        }

        return PersonalResponseEngine.analyze(
            PersonalResponseEngine.Input(
                plans = snapshots,
                planVersionReasons = versionReasons,
                cheats = cheats.all(profileId).first(),
                workouts = workouts.all(profileId).first(),
                bia = bia.all(profileId).first(),
                bodyMeasurements = bodyMeasurements.all(profileId).first(),
                nowEpochMillis = nowEpochMillis,
                lookbackDays = lookbackDays,
            )
        )
    }

    suspend fun promptContext(
        nowEpochMillis: Long = System.currentTimeMillis(),
        lookbackDays: Int = 56,
    ): String {
        val history = summarizeActiveProfile(nowEpochMillis, lookbackDays)?.toPromptContext().orEmpty()
        val profileId = activeProfileStore.currentIdOrNull() ?: return history
        val goals = expectationGoals.all(profileId).first()
            .take(8)
            .joinToString("|") {
                "${it.periodStartEpochDay}:${it.periodEndEpochDay}:${it.status}:days=${it.plannedDays}:deficit=${it.theoreticalDeficitKcal ?: "?"}:range=${it.expectedFatLossMinKg ?: "?"}-${it.expectedFatLossMaxKg ?: "?"}"
            }
        return if (goals.isBlank()) history else "$history\nGOAL_OUTCOMES:$goals\nSAFE:goal outcomes are user-confirmed context for evaluating the previous plan; never override local numerical targets automatically"
    }
}
