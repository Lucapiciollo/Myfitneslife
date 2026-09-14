package com.myfitai.app.domain.personalization

import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.WorkoutRepository
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
    ): String = summarizeActiveProfile(nowEpochMillis, lookbackDays)?.toPromptContext().orEmpty()
}
