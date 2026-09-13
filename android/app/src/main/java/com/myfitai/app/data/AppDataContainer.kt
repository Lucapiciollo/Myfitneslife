package com.myfitai.app.data

import android.content.Context
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.ProfilePhotoStore
import com.myfitai.app.data.repository.*

/**
 * Composition root del layer dati. Activity/ViewModel non devono conoscere i DAO.
 */
class AppDataContainer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val db = MyFitAiDatabase.getInstance(appContext)

    val activeProfileStore = ActiveProfileStore(appContext)
    val profilePhotoStore = ProfilePhotoStore(appContext)

    val userProfileRepository = UserProfileRepository(db)
    val biaRepository = BiaRepository(db)
    val bodyMeasurementRepository = BodyMeasurementRepository(db)
    val workoutRepository = WorkoutRepository(db)
    val mealPlanRepository = MealPlanRepository(db)
    val cheatEntryRepository = CheatEntryRepository(db)
    val weeklyReviewRepository = WeeklyReviewRepository(db)

    companion object {
        @Volatile private var instance: AppDataContainer? = null

        fun get(context: Context): AppDataContainer = instance ?: synchronized(this) {
            instance ?: AppDataContainer(context.applicationContext).also { instance = it }
        }
    }
}
