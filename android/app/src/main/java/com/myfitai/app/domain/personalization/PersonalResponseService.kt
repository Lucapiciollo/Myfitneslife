package com.myfitai.app.domain.personalization

import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.BodyExpectationGoalRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/** Reads only the active profile's history and builds a bounded local summary. */
class PersonalResponseService(
    private val activeProfileStore: ActiveProfileStore,
    private val plans: MealPlanRepository,
    private val cheats: CheatEntryRepository,
    private val bia: BiaRepository,
    private val bodyMeasurements: BodyMeasurementRepository,
    private val expectationGoals: BodyExpectationGoalRepository,
) {
    suspend fun summarizeActiveProfile(
        nowEpochMillis: Long = System.currentTimeMillis(),
        lookbackDays: Int = 56,
    ): PersonalResponseEngine.Summary? {
        val profileId = activeProfileStore.currentIdOrNull() ?: return null
        return summarize(profileId, nowEpochMillis, lookbackDays)
    }

    /** Summary of exactly [profileId]; the active profile is never consulted. */
    suspend fun summarize(
        profileId: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
        lookbackDays: Int = 56,
    ): PersonalResponseEngine.Summary {
        require(lookbackDays in 7..365)
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
                bia = bia.all(profileId).first(),
                bodyMeasurements = bodyMeasurements.all(profileId).first(),
                nowEpochMillis = nowEpochMillis,
                lookbackDays = lookbackDays,
            )
        )
    }

    /**
     * Bounded history context. Background jobs and services that already know the target profile MUST pass
     * [profileId]: only when it is null the currently active profile is used (UI flows on the active profile).
     */
    suspend fun promptContext(
        nowEpochMillis: Long = System.currentTimeMillis(),
        lookbackDays: Int = 56,
        profileId: Long? = null,
    ): String {
        val resolvedProfileId = resolveProfileId(profileId, activeProfileStore.currentIdOrNull()) ?: return ""
        return promptContextFor(resolvedProfileId, nowEpochMillis, lookbackDays)
    }

    private suspend fun promptContextFor(profileId: Long, nowEpochMillis: Long, lookbackDays: Int): String {
        val history = summarize(profileId, nowEpochMillis, lookbackDays).toPromptContext()
        val goals = expectationGoals.all(profileId).first()
            .take(8)
            .joinToString("|") {
                "${it.periodStartEpochDay}:${it.periodEndEpochDay}:${it.status}:days=${it.plannedDays}:deficit=${it.theoreticalDeficitKcal ?: "?"}:range=${it.expectedFatLossMinKg ?: "?"}-${it.expectedFatLossMaxKg ?: "?"}"
            }
        return if (goals.isBlank()) history else "$history\nGOAL_OUTCOMES:$goals\nSAFE:goal outcomes are user-confirmed context for evaluating the previous plan; never override local numerical targets automatically"
    }

    companion object {
        /** The explicit profile of the caller always wins over the active one. */
        internal fun resolveProfileId(explicit: Long?, active: Long?): Long? = explicit ?: active
    }
}
