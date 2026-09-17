package com.myfitai.app.data

import android.content.Context
import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.profile.ProfilePhotoStore
import com.myfitai.app.data.repository.*
import com.myfitai.app.domain.body.BodyProportionAnalysisService
import com.myfitai.app.domain.body.BiaImportService
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.export.ProfileExportService
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.FoodConsumptionService
import com.myfitai.app.domain.food.MealAlternativeService
import com.myfitai.app.domain.food.NutritionAdviceService
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.food.PlanReviewService
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.progress.ProgressAnalysisPreferences
import com.myfitai.app.domain.progress.ProgressAnalysisScheduler
import com.myfitai.app.domain.progress.ProgressAnalysisService
import com.myfitai.app.domain.review.WeeklyReviewService
import com.myfitai.app.domain.data.DataDeletionService
import com.myfitai.app.notifications.NotificationScheduler

/**
 * Composition root del layer dati/app. Activity/ViewModel non devono conoscere i DAO.
 */
class AppDataContainer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val db = MyFitAiDatabase.getInstance(appContext)

    val activeProfileStore = ActiveProfileStore(appContext)
    val mealCountPreferences = MealCountPreferences(appContext)
    val profilePhotoStore = ProfilePhotoStore(appContext)
    val progressAnalysisPreferences = ProgressAnalysisPreferences(appContext)

    val userProfileRepository = UserProfileRepository(db)
    val biaRepository = BiaRepository(db)
    val bodyMeasurementRepository = BodyMeasurementRepository(db)
    val workoutRepository = WorkoutRepository(db)
    val mealPlanRepository = MealPlanRepository(db)
    val cheatEntryRepository = CheatEntryRepository(db)
    val calorieRecoveryRepository = CalorieRecoveryRepository(db)
    val foodConsumptionRepository = FoodConsumptionRepository(db)
    val foodConsumptionService = FoodConsumptionService(foodConsumptionRepository, activeProfileStore)
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
    val bodyProportionAnalysisService = BodyProportionAnalysisService(aiRuntimeService)
    val biaImportService = BiaImportService(aiRuntimeService)
    val planReviewService = PlanReviewService(aiRuntimeService)

    val progressAnalysisService = ProgressAnalysisService(
        aiRuntime = aiRuntimeService,
        calculations = profileCalculationService,
        profiles = userProfileRepository,
        workouts = workoutRepository,
        cheats = cheatEntryRepository,
        activeProfileStore = activeProfileStore,
        preferences = progressAnalysisPreferences,
    )
    val progressAnalysisScheduler = ProgressAnalysisScheduler(appContext, progressAnalysisPreferences)
    val dataDeletionService = DataDeletionService(
        db = db,
        activeProfileStore = activeProfileStore,
        progressAnalysisPreferences = progressAnalysisPreferences,
        progressAnalysisScheduler = progressAnalysisScheduler,
    )

    val nutritionPlanGenerationService = NutritionPlanGenerationService(
        aiRuntime = aiRuntimeService,
        calculations = profileCalculationService,
        profiles = userProfileRepository,
        workouts = workoutRepository,
        plans = mealPlanRepository,
        cheats = cheatEntryRepository,
        recovery = calorieRecoveryRepository,
        activeProfileStore = activeProfileStore,
        personalResponse = personalResponseService,
        mealCountPreferences = mealCountPreferences,
        planReview = planReviewService,
    )

    val cheatAdjustmentService = CheatAdjustmentService(
        aiRuntime = aiRuntimeService,
        plans = mealPlanRepository,
        cheats = cheatEntryRepository,
        profiles = userProfileRepository,
        activeProfileStore = activeProfileStore,
    )

    val nutritionAdviceService = NutritionAdviceService(
        aiRuntime = aiRuntimeService,
        profiles = userProfileRepository,
        plans = mealPlanRepository,
        cheats = cheatEntryRepository,
        activeProfileStore = activeProfileStore,
    )

    val mealAlternativeService = MealAlternativeService(
        aiRuntime = aiRuntimeService,
        profiles = userProfileRepository,
        plans = mealPlanRepository,
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
        foodConsumptions = foodConsumptionRepository,
    )

    val notificationScheduler = NotificationScheduler(
        context = appContext,
        plans = mealPlanRepository,
        activeProfileStore = activeProfileStore,
    )

    val profileExportService = ProfileExportService(
        context = appContext,
        db = db,
        activeProfileStore = activeProfileStore,
    )

    companion object {
        @Volatile private var instance: AppDataContainer? = null

        fun get(context: Context): AppDataContainer = instance ?: synchronized(this) {
            instance ?: AppDataContainer(context.applicationContext).also { instance = it }
        }
    }
}
