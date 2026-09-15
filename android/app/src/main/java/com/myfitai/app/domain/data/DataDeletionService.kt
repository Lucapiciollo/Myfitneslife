package com.myfitai.app.domain.data

import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore

/** Deletes only records belonging to the active profile; profile and credentials are untouched. */
class DataDeletionService(
    private val db: MyFitAiDatabase,
    private val activeProfileStore: ActiveProfileStore,
) {
    suspend fun deleteMealPlans() = withProfile { db.mealPlanDao().deleteByProfile(it) }
    suspend fun deleteBiaMeasurements() = withProfile { db.biaMeasurementDao().deleteByProfile(it) }
    suspend fun deleteBodyMeasurements() = withProfile { db.bodyMeasurementDao().deleteByProfile(it) }
    suspend fun deleteWorkouts() = withProfile { db.workoutDao().deleteByProfile(it) }
    suspend fun deleteCheatEntries() = withProfile { db.cheatEntryDao().deleteByProfile(it) }
    suspend fun deleteWeeklyReviews() = withProfile { db.weeklyReviewDao().deleteByProfile(it) }

    suspend fun deleteRecordedData() = withProfile { profileId ->
        db.withTransaction {
            db.mealPlanDao().deleteByProfile(profileId)
            db.biaMeasurementDao().deleteByProfile(profileId)
            db.bodyMeasurementDao().deleteByProfile(profileId)
            db.workoutDao().deleteByProfile(profileId)
            db.cheatEntryDao().deleteByProfile(profileId)
            db.weeklyReviewDao().deleteByProfile(profileId)
        }
    }

    private suspend fun <T> withProfile(block: suspend (Long) -> T): T =
        block(activeProfileStore.currentIdOrNull() ?: error("Nessun profilo attivo"))
}
