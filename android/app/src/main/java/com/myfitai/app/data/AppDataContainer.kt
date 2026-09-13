package com.myfitai.app.data

import android.content.Context
import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.ProfilePhotoStore
import com.myfitai.app.data.repository.*
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.review.WeeklyReviewService
import com.myfitai.app.notifications.NotificationScheduler

/**
 * Composition root del layer dati/app. Activity/ViewModel non devono conoscere i DAO.
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

    val profileCalculationService = ProfileCalculationService(
        profiles = userProfileRepository,
        bia = biaRepository,
        bodyMeasurements = bodyMeasurementRepository,
        activeProfileStore = activeProfileStore,
    )

    val personalResponseService = PersonalResponseService(
        activeProfileStore = activeProfileStore,
        plans = mealPlanRepository,
        cheats = cheatEntryRepository,
        workouts = workoutRepository,
        bia = biaRepository,
        bodyMeasurements = bodyMeasurementRepository,
    )

    val aiRuntimeService = AiRuntimeService(appContext)

    val nutritionPlanGenerationService = NutritionPlanGenerationService(
        aiRuntime = aiRuntimeService,
        calculations = profileCalculationService,
        profiles = userProfileRepository,
        workouts = workoutRepository,
        plans = mealPlanRepository,
        activeProfileStore = activeProfileStore,
        personalResponse = personalResponseService,
    )

    val cheatAdjustmentService = CheatAdjustmentService(
        aiRuntime = aiRuntimeService,
        plans = mealPlanRepository,
        cheats = cheatEntryRepository,
        activeProfileStore = activeProfileStore,
    )

    val weeklyReviewService = WeeklyReviewService(
        aiRuntime = aiRuntimeService,
        reviews = weeklyReviewRepository,
        plans = mealPlanRepository,
        workouts = workoutRepository,
        cheats = cheatEntryRepository,
        bia = biaRepository,
        bodyMeasurements = bodyMeasurementRepository,
        personalResponse = personalResponseService,
        activeProfileStore = activeProfileStore,
    )

    val notificationScheduler = NotificationScheduler(
        context = appContext,
        plans = mealPlanRepository,
        activeProfileStore = activeProfileStore,
    )

    companion object {
        @Volatile private var instance: AppDataContainer? = null

        fun get(context: Context): AppDataContainer = instance ?: synchronized(this) {
            instance ?: AppDataContainer(context.applicationContext).also { instance = it }
        }
    }
}
