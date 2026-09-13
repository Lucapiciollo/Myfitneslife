package com.myfitai.app.data

import android.content.Context
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.repository.*

/**
 * Composition root minimale per il layer dati. Le Activity/ViewModel non devono conoscere i DAO.
 * In futuro potrà essere sostituito da DI senza cambiare i repository.
 */
class AppDataContainer private constructor(context: Context) {
    private val db = MyFitAiDatabase.getInstance(context)

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
