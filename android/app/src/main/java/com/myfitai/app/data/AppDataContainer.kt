package com.myfitai.app.data

import android.content.Context
import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.profile.NutritionAutoGenerationPreferences
import com.myfitai.app.data.profile.ProfilePhotoStore
import com.myfitai.app.data.repository.*
import com.myfitai.app.domain.ai.AiJobRegistry
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.ai.BodyProportionsAiJobHandler
import com.myfitai.app.domain.ai.CheatAdjustmentAiJobHandler
import com.myfitai.app.domain.ai.CheatUnderstandingAiJobHandler
import com.myfitai.app.domain.ai.AiImageJobStore
import com.myfitai.app.domain.ai.MealAlternativesAiJobHandler
import com.myfitai.app.domain.ai.BiaImportAiJobHandler
import com.myfitai.app.domain.ai.NutritionAdviceAiJobHandler
import com.myfitai.app.domain.ai.NutritionPathAiJobHandler
import com.myfitai.app.domain.ai.NutritionPathTrigger
import com.myfitai.app.domain.ai.ProgressAnalysisAiJobHandler
import com.myfitai.app.domain.ai.WeeklyPlanAiJobHandler
import com.myfitai.app.domain.ai.WeeklyReviewAiJobHandler
import com.myfitai.app.domain.body.BodyProportionEngine
import kotlinx.coroutines.flow.first
import com.myfitai.app.domain.body.BodyProportionAnalysisService
import com.myfitai.app.domain.body.BiaImportService
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.export.ProfileExportService
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.FoodConsumptionService
import com.myfitai.app.domain.food.MealAlternativeService
import com.myfitai.app.domain.food.NutritionAdviceService
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.food.NutritionAutoGenerationScheduler
import com.myfitai.app.data.profile.NutritionMealSchedulePreferences
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
    val nutritionAutoGenerationPreferences = NutritionAutoGenerationPreferences(appContext)
    val nutritionMealSchedulePreferences = NutritionMealSchedulePreferences(appContext)
    val profilePhotoStore = ProfilePhotoStore(appContext)
    val progressAnalysisPreferences = ProgressAnalysisPreferences(appContext)

    val userProfileRepository = UserProfileRepository(db)
    val biaRepository = BiaRepository(db)
    val bodyMeasurementRepository = BodyMeasurementRepository(db)
    val workoutRepository = WorkoutRepository(db)
    val workoutEnergyExpenditureRepository = WorkoutEnergyExpenditureRepository(db)
    val mealPlanRepository = MealPlanRepository(db)
    val cheatEntryRepository = CheatEntryRepository(db)
    val nutritionRecoveryRepository = NutritionRecoveryRepository(db)
    val foodConsumptionRepository = FoodConsumptionRepository(db)
    val foodConsumptionService = FoodConsumptionService(foodConsumptionRepository, activeProfileStore, nutritionRecoveryRepository)
    val weeklyReviewRepository = WeeklyReviewRepository(db)

    val profileCalculationService = ProfileCalculationService(
        profiles = userProfileRepository,
        bia = biaRepository,
        bodyMeasurements = bodyMeasurementRepository,
        activeProfileStore = activeProfileStore,
        exerciseEnergy = workoutEnergyExpenditureRepository,
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
        activeProfileStore = activeProfileStore,
        personalResponse = personalResponseService,
        mealCountPreferences = mealCountPreferences,
        mealSchedule = nutritionMealSchedulePreferences,
        planReview = planReviewService,
        recovery = nutritionRecoveryRepository,
    )

    val cheatAdjustmentService = CheatAdjustmentService(
        aiRuntime = aiRuntimeService,
        plans = mealPlanRepository,
        cheats = cheatEntryRepository,
        activeProfileStore = activeProfileStore,
        recovery = nutritionRecoveryRepository,
        consumptions = foodConsumptionRepository,
        exerciseEnergy = workoutEnergyExpenditureRepository,
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
        recovery = nutritionRecoveryRepository,
        exerciseEnergy = workoutEnergyExpenditureRepository,
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
        mealCountPreferences = mealCountPreferences,
        mealSchedulePreferences = nutritionMealSchedulePreferences,
    )

    val aiJobResultRepository = AiJobResultRepository(db.aiJobResultDao())
    val aiJobScheduler = AiJobScheduler(appContext)
    val nutritionPathTrigger = NutritionPathTrigger(userProfileRepository, biaRepository, bodyMeasurementRepository, aiJobScheduler)
    val nutritionAutoGenerationScheduler = NutritionAutoGenerationScheduler(appContext, nutritionAutoGenerationPreferences)
    val aiImageJobStore = AiImageJobStore(appContext)

    /** Every AI operation runs through this registry, so new ones only add a handler here. */
    val aiJobRegistry = AiJobRegistry(
        mapOf(
            AiJobType.WEEKLY_PLAN to WeeklyPlanAiJobHandler(nutritionPlanGenerationService),
            AiJobType.PROGRESS_ANALYSIS to ProgressAnalysisAiJobHandler(progressAnalysisService),
            AiJobType.WEEKLY_REVIEW to WeeklyReviewAiJobHandler(weeklyReviewService),
            AiJobType.NUTRITION_ADVICE to NutritionAdviceAiJobHandler(nutritionAdviceService),
            AiJobType.NUTRITION_PATH to NutritionPathAiJobHandler(aiRuntimeService) { profileId ->
                val snapshot = profileCalculationService.profileSnapshot(profileId)
                val profile = userProfileRepository.get(profileId)
                val bia = biaRepository.all(profileId).first().takeLast(5)
                val body = bodyMeasurementRepository.all(profileId).first().takeLast(5)
                val workouts = workoutRepository.all(profileId).first().takeLast(14)
                buildString {
                    appendLine("P:${profile?.goal ?: "?"}|${profile?.activityLevel ?: "?"}|${profile?.heightCm ?: "?"}|${profile?.currentWeightKg ?: "?"}")
                    appendLine("BIA:${bia.size}|${bia.firstOrNull()?.weightKg ?: "?"}|${bia.lastOrNull()?.weightKg ?: "?"}|${bia.lastOrNull()?.bodyFatPercent ?: "?"}|${bia.lastOrNull()?.muscleMassKg ?: "?"}")
                    appendLine("BODY:${body.size}|${body.firstOrNull()?.waistCm ?: "?"}|${body.lastOrNull()?.waistCm ?: "?"}|${body.lastOrNull()?.abdomenCm ?: "?"}")
                    appendLine("WO:${workouts.count { !it.isRestDay }}|${workouts.count { it.isRestDay }}")
                    appendLine("DATA:${if (snapshot?.latestBiaTimestamp != null && snapshot.latestBodyMeasurementTimestamp != null) "SUFFICIENT" else "INCOMPLETE"}")
                }
            },
            AiJobType.CHEAT_UNDERSTANDING to CheatUnderstandingAiJobHandler(cheatAdjustmentService, aiImageJobStore),
            AiJobType.CHEAT_ADJUSTMENT to CheatAdjustmentAiJobHandler(cheatAdjustmentService),
            AiJobType.MEAL_ALTERNATIVES to MealAlternativesAiJobHandler(mealAlternativeService),
            AiJobType.BIA_IMPORT to BiaImportAiJobHandler(biaImportService, aiImageJobStore),
            AiJobType.BODY_PROPORTIONS to BodyProportionsAiJobHandler(bodyProportionAnalysisService) { profileId ->
                val profile = userProfileRepository.get(profileId)
                val latest = bodyMeasurementRepository.latest(profileId).first()
                BodyProportionEngine.analyze(latest, profile?.heightCm)
            },
        )
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
