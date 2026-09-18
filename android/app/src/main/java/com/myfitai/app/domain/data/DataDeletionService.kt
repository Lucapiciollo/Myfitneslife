package com.myfitai.app.domain.data

import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.domain.progress.ProgressAnalysisPreferences
import com.myfitai.app.domain.progress.ProgressAnalysisScheduler

/** Deletes only records belonging to the active profile; profile and credentials are untouched. */
class DataDeletionService(
    private val db: MyFitAiDatabase,
    private val activeProfileStore: ActiveProfileStore,
    private val progressAnalysisPreferences: ProgressAnalysisPreferences? = null,
    private val progressAnalysisScheduler: ProgressAnalysisScheduler? = null,
) {
    suspend fun deleteMealPlans() = withProfile { db.mealPlanDao().deleteByProfile(it) }

    suspend fun deleteFoodConsumptions() = withProfile { db.foodConsumptionDao().deleteByProfile(it) }

    suspend fun deleteBiaMeasurements() = withProfile { profileId ->
        db.biaMeasurementDao().deleteByProfile(profileId)
        invalidateProgressAnalysis(profileId)
    }

    suspend fun deleteBodyMeasurements() = withProfile { profileId ->
        db.bodyMeasurementDao().deleteByProfile(profileId)
        invalidateProgressAnalysis(profileId)
    }

    suspend fun deleteWorkouts() = withProfile { profileId ->
        db.workoutDao().deleteByProfile(profileId)
        db.workoutEnergyExpenditureDao().deleteByProfile(profileId)
        invalidateProgressAnalysis(profileId)
    }

    suspend fun deleteCheatEntries() = withProfile { profileId ->
        db.cheatEntryDao().deleteByProfile(profileId)
        invalidateProgressAnalysis(profileId)
    }

    suspend fun deleteWeeklyReviews() = withProfile { db.weeklyReviewDao().deleteByProfile(it) }

    suspend fun deleteRecordedData() = withProfile { profileId ->
        db.withTransaction {
            db.mealPlanDao().deleteByProfile(profileId)
            db.foodConsumptionDao().deleteByProfile(profileId)
            db.biaMeasurementDao().deleteByProfile(profileId)
            db.bodyMeasurementDao().deleteByProfile(profileId)
            db.workoutDao().deleteByProfile(profileId)
            db.workoutEnergyExpenditureDao().deleteByProfile(profileId)
            db.cheatEntryDao().deleteByProfile(profileId)
            db.weeklyReviewDao().deleteByProfile(profileId)
        }
        invalidateProgressAnalysis(profileId)
    }

    private fun invalidateProgressAnalysis(profileId: Long) {
        progressAnalysisScheduler?.cancel(profileId)
        progressAnalysisPreferences?.clearProfile(profileId)
    }

    private suspend fun <T> withProfile(block: suspend (Long) -> T): T =
        block(activeProfileStore.currentIdOrNull() ?: error("Nessun profilo attivo"))
}
