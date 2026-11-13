package com.myfitai.app.domain.data

import android.content.Context
import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.data.profile.NutritionPlanUpdatePreferences
import com.myfitai.app.data.profile.ProfilePhotoStore
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.body.BiaProgressCoachScheduler
import com.myfitai.app.domain.food.NutritionPlanScheduler
import com.myfitai.app.domain.progress.ProgressAnalysisPreferences
import com.myfitai.app.domain.progress.ProgressAnalysisScheduler
import com.myfitai.app.notifications.NotificationScheduler

/** Deletes only records belonging to the active profile; profile and credentials are untouched. */
class DataDeletionService(
    private val db: MyFitAiDatabase,
    private val activeProfileStore: ActiveProfileStore,
    private val progressAnalysisPreferences: ProgressAnalysisPreferences? = null,
    private val progressAnalysisScheduler: ProgressAnalysisScheduler? = null,
    private val aiJobScheduler: AiJobScheduler? = null,
    private val biaProgressCoachScheduler: BiaProgressCoachScheduler? = null,
    private val nutritionPlanScheduler: NutritionPlanScheduler? = null,
    private val nutritionPlanSchedulePreferences: NutritionPlanSchedulePreferences? = null,
    private val nutritionPlanUpdatePreferences: NutritionPlanUpdatePreferences? = null,
    private val aiAutomationPreferences: AiAutomationPreferences? = null,
    private val mealCountPreferences: MealCountPreferences? = null,
    private val profilePhotoStore: ProfilePhotoStore? = null,
    private val notificationScheduler: NotificationScheduler? = null,
    private val context: Context? = null,
) {
    suspend fun deleteMealPlans() = withProfile { db.mealPlanDao().deleteByProfile(it) }

    suspend fun deleteFoodConsumptions() = withProfile { db.foodConsumptionDao().deleteByProfile(it) }

    suspend fun deleteBiaMeasurements() = withProfile { profileId ->
        // The AI analyses refer to the readings: removing only the readings would leave orphan results.
        db.withTransaction {
            db.biaAnalysisResultDao().deleteByProfile(profileId)
            db.biaMeasurementDao().deleteByProfile(profileId)
        }
        invalidateProgressAnalysis(profileId)
    }

    suspend fun deleteBodyMeasurements() = withProfile { profileId ->
        db.bodyMeasurementDao().deleteByProfile(profileId)
        invalidateProgressAnalysis(profileId)
    }

    suspend fun deleteCheatEntries() = withProfile { profileId ->
        db.cheatEntryDao().deleteByProfile(profileId)
        invalidateProgressAnalysis(profileId)
    }

    suspend fun deleteWeeklyReviews() = withProfile { db.weeklyReviewDao().deleteByProfile(it) }

    suspend fun deleteRecordedData() = withProfile { profileId ->
        deleteProfileRecords(profileId)
    }

    /** Removes the selected profile and every record/preferences/work item owned by it. */
    suspend fun deleteProfile(profileId: Long): UserProfileEntity {
        require(profileId > 0) { "Profilo non valido" }
        val profile = db.userProfileDao().get(profileId) ?: error("Profilo non disponibile")
        val replacementId = db.userProfileDao().getAllExcept(profileId).firstOrNull()?.id

        cancelProfileJobs(profileId)
        if (profileId == activeProfileStore.currentIdOrNull()) {
            aiJobScheduler?.cancelAll(AiJobType.WEEKLY_PLAN, profileId)
            aiJobScheduler?.cancelAll(AiJobType.NUTRITION_PATH, profileId)
        }
        db.withTransaction {
            deleteProfileRows(profileId)
            db.userProfileDao().delete(profile)
        }
        invalidateProgressAnalysis(profileId)
        profilePhotoStore?.delete(profile.photoPath)
        clearProfilePreferences(profileId)
        activeProfileStore.onProfileDeleted(profileId, replacementId)
        notificationScheduler?.refresh()
        return profile
    }

    private suspend fun deleteProfileRecords(profileId: Long) {
        db.withTransaction { deleteProfileRows(profileId) }
        invalidateProgressAnalysis(profileId)
    }

    private suspend fun deleteProfileRows(profileId: Long) {
        db.biaAnalysisResultDao().deleteByProfile(profileId)
        db.bodyExpectationGoalDao().deleteByProfile(profileId)
        db.biaMeasurementDao().deleteByProfile(profileId)
        db.bodyMeasurementDao().deleteByProfile(profileId)
        db.mealPlanDao().deleteByProfile(profileId)
        db.foodConsumptionDao().deleteByProfile(profileId)
        db.cheatEntryDao().deleteByProfile(profileId)
        db.weeklyReviewDao().deleteByProfile(profileId)
    }

    private fun cancelProfileJobs(profileId: Long) {
        AiJobType.entries.forEach { type -> aiJobScheduler?.cancelAll(type, profileId) }
        progressAnalysisScheduler?.cancel(profileId)
        biaProgressCoachScheduler?.cancel(profileId)
        nutritionPlanScheduler?.cancel(profileId)
    }

    private fun clearProfilePreferences(profileId: Long) {
        progressAnalysisPreferences?.clearProfile(profileId)
        aiAutomationPreferences?.clearProfile(profileId)
        nutritionPlanSchedulePreferences?.clearProfile(profileId)
        nutritionPlanUpdatePreferences?.clearProfile(profileId)
        mealCountPreferences?.clearProfile(profileId)
        clearLegacyTrainingPreferences(profileId)
    }

    /**
     * Dati legacy: allenamenti e programma di allenamento non esistono piu nell'app, ma le vecchie installazioni
     * conservano ancora le loro SharedPreferences ("workout_preferences", "training_program_preferences").
     * Le chiavi sono nel formato "<nome>_<profileId>": si rimuovono solo quelle del profilo eliminato.
     */
    private fun clearLegacyTrainingPreferences(profileId: Long) {
        val appContext = context?.applicationContext ?: return
        LEGACY_TRAINING_PREFERENCE_FILES.forEach { name ->
            val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val stale = prefs.all.keys.filter { it.endsWith("_$profileId") }
            if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
        }
    }

    private fun invalidateProgressAnalysis(profileId: Long) {
        progressAnalysisScheduler?.cancel(profileId)
        progressAnalysisPreferences?.clearProfile(profileId)
    }

    private suspend fun <T> withProfile(block: suspend (Long) -> T): T =
        block(activeProfileStore.currentIdOrNull() ?: error("Nessun profilo attivo"))

    private companion object {
        val LEGACY_TRAINING_PREFERENCE_FILES = listOf("workout_preferences", "training_program_preferences")
    }
}
