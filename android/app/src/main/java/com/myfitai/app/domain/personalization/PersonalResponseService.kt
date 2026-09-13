package com.myfitai.app.domain.personalization

import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.WorkoutRepository
import kotlinx.coroutines.flow.first

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
        val profileId = activeProfileStore.currentIdOrNull() ?: return null
        val planRows = plans.plans(profileId).first()
        val snapshots = planRows.mapNotNull { row ->
            plans.loadLatestSnapshot(profileId, row.weekStartEpochDay)
        }

        return PersonalResponseEngine.analyze(
            PersonalResponseEngine.Input(
                plans = snapshots,
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
