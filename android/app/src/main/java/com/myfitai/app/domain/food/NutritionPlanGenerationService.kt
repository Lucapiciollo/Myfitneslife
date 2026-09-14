package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.data.repository.SupplementDraft
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class NutritionPlanGenerationService(
    private val aiRuntime: AiRuntimeGateway,
    private val calculations: ProfileCalculationService,
    private val profiles: UserProfileRepository,
    private val workouts: WorkoutRepository,
    private val plans: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val personalResponse: PersonalResponseService,
    private val time: TimeProvider = SystemTimeProvider,
) {
    sealed class GenerationException(message: String) : Exception(message) {
        class NeedsInput(val fields: List<String>) : GenerationException("NEEDS_INPUT: ${fields.joinToString()}")
        class PastWeek : GenerationException("PAST_WEEK_READ_ONLY")
        class InvalidAiOutput(message: String) : GenerationException(message)
    }

    data class Result(
        val planId: Long,
        val versionId: Long,
        val provider: String,
        val model: String,
        val agentValidation: NutritionPlanContract.AgentValidation,
    )

    suspend fun generateWeek(weekStart: LocalDate): Result {
        val monday = weekStart.minusDays((weekStart.dayOfWeek.value - 1).toLong())
        if (monday.plusDays(6).isBefore(time.today())) throw GenerationException.PastWeek()

        val profileId = activeProfileStore.currentIdOrNull() ?: throw GenerationException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId) ?: throw GenerationException.NeedsInput(listOf("profilo"))
        val snapshot = calculations.activeProfileSnapshot(time.today()) ?: throw GenerationException.NeedsInput(listOf("dati profilo"))
        val calc = snapshot.calculation
        val missing = buildList {
            if (calc.targetKcal == null) add("target calorie")
            if (calc.proteinG == null) add("proteine")
            if (calc.carbsG == null) add("carboidrati")
            if (calc.fatG == null) add("grassi")
        }
        if (missing.isNotEmpty()) throw GenerationException.NeedsInput(missing)

        val targets = NutritionBusinessValidator.Targets(
            kcal = calc.targetKcal!!,
            proteinG = calc.proteinG!!,
            carbsG = calc.carbsG!!,
            fatG = calc.fatG!!,
        )

        val zone = time.zoneId
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val weekWorkouts = workouts.between(profileId, from, to).first()
        val sportsMode = SportsNutritionClassifier.classify(profile.activityLevel, weekWorkouts)
        val personalContext = personalResponse.promptContext(nowEpochMillis = time.nowEpochMillis())

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildUserPrompt(
                monday = monday,
                profile = profile,
                targets = targets,
                workoutContext = weekWorkouts.map { w ->
                    val dt = java.time.Instant.ofEpochMilli(w.startedAtEpochMillis).atZone(zone)
                    "${dt.toLocalDate()} ${dt.toLocalTime()} | ${w.type} | ${w.title} | ${w.durationMinutes ?: 0} min | rest=${w.isRestDay}"
                },
                personalContext = personalContext,
                snapshot = snapshot,
                sportsMode = sportsMode,
            ),
            schemaName = NutritionPlanContract.SCHEMA_NAME,
            schemaJson = NutritionPlanContract.schemaJson,
            maxOutputTokens = 16_000,
            allowSchemaFallback = true,
        )

        var parsed: NutritionPlanContract.Response? = null
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 1,
            businessValidator = { json ->
                runCatching {
                    val response = NutritionPlanContract.parse(json)
                    NutritionPlanContract.validateBusiness(response, monday, targets, sportsMode).getOrThrow()
                    parsed = response
                }
            },
        )
        val response = parsed ?: runCatching { NutritionPlanContract.parse(validated.jsonText) }
            .getOrElse { throw GenerationException.InvalidAiOutput("INVALID_SCHEMA") }

        val draft = PlanVersionDraft(
            source = validated.provider.name,
            reason = "AI_GENERATION",
            targetKcal = targets.kcal.toInt(),
            targetProteinG = targets.proteinG.toFloat(),
            targetCarbsG = targets.carbsG.toFloat(),
            targetFatG = targets.fatG.toFloat(),
            days = response.days.sortedBy { it.dateEpochDay }.map { day ->
                DayDraft(
                    dateEpochDay = day.dateEpochDay,
                    totalKcal = day.totalKcal,
                    proteinG = day.proteinG,
                    carbsG = day.carbsG,
                    fatG = day.fatG,
                    meals = day.meals.sortedBy { it.timeMinutes }.map { meal ->
                        MealDraft(
                            type = meal.type,
                            title = meal.title,
                            timeMinutes = meal.timeMinutes,
                            kcal = meal.kcal,
                            proteinG = meal.proteinG,
                            carbsG = meal.carbsG,
                            fatG = meal.fatG,
                            preparation = meal.preparation,
                            ingredients = meal.ingredients.map { ingredient ->
                                IngredientDraft(
                                    name = ingredient.name,
                                    quantity = ingredient.quantity,
                                    unit = ingredient.unit,
                                    displayDose = ingredient.displayDose,
                                    weightState = ingredient.weightState,
                                    nutritionConfidence = ingredient.nutritionConfidence,
                                    category = ingredient.category,
                                )
                            },
                        )
                    },
                    supplements = day.supplements.map { supplement ->
                        SupplementDraft(
                            kind = supplement.kind,
                            name = supplement.name,
                            dose = supplement.dose,
                            unit = supplement.unit,
                            timeMinutes = supplement.timeMinutes,
                            kcal = supplement.kcal,
                            proteinG = supplement.proteinG,
                            carbsG = supplement.carbsG,
                            fatG = supplement.fatG,
                            notes = supplement.notes,
                        )
                    },
                    hydrationNote = day.hydrationNote.takeIf { it.isNotBlank() },
                )
            },
        )

        val existing = plans.getPlanForWeek(profileId, monday.toEpochDay())
        val planId = existing?.id ?: plans.createPlan(profileId, monday.toEpochDay(), time.nowEpochMillis())
        val versionId = plans.appendVersion(profileId, planId, time.nowEpochMillis(), draft)
        return Result(planId, versionId, validated.provider.name, validated.model, response.agentValidation)
    }

    private fun buildUserPrompt(
        monday: LocalDate,
        profile: com.myfitai.app.data.local.entity.UserProfileEntity,
        targets: NutritionBusinessValidator.Targets,
        workoutContext: List<String>,
        personalContext: String,
        snapshot: ProfileCalculationService.Snapshot,
        sportsMode: SportsNutritionClassifier.Mode,
    ): String = buildString {
        appendLine("Generate the nutrition plan for the week starting ${monday.toEpochDay()} ($monday).")
        appendLine("DAILY_TARGETS_AUTHORITATIVE:kcal=${targets.kcal.toInt()}|P=${String.format(Locale.US, "%.1f", targets.proteinG)}|C=${String.format(Locale.US, "%.1f", targets.carbsG)}|F=${String.format(Locale.US, "%.1f", targets.fatG)}|tolerance=3%")
        appendLine("SPORT_MODE:${sportsMode.name}")
        appendLine("PROFILE_GOAL:${profile.goal ?: "unknown"}|ACTIVITY:${profile.activityLevel ?: "unknown"}")
        appendLine("WAKE:${profile.wakeTimeMinutes ?: "unknown"}|SLEEP:${profile.sleepTimeMinutes ?: "unknown"}")
        appendLine("PREFERENCES:${profile.dietaryPreferencesJson ?: "none"}")
        appendLine("BIA_CONTEXT:weightKg=${snapshot.latestWeightKg ?: "unknown"}|bodyFatPct=${snapshot.latestBodyFatPercent ?: "unknown"}|muscleKg=${snapshot.latestMuscleMassKg ?: "unknown"}|skeletalMuscleKg=${snapshot.latestSkeletalMuscleKg ?: "unknown"}|bodyWaterPct=${snapshot.latestBodyWaterPercent ?: "unknown"}|waistCm=${snapshot.latestWaistCm ?: "unknown"}")
        appendLine("TRENDS:weightDelta=${snapshot.weightTrend.delta ?: "unknown"}|bodyFatDelta=${snapshot.bodyFatTrend.delta ?: "unknown"}|muscleDelta=${snapshot.muscleMassTrend.delta ?: "unknown"}|waistDelta=${snapshot.waistTrend.delta ?: "unknown"}|recomposition=${snapshot.recompositionState}")
        appendLine("PLANNED_WORKOUTS:")
        if (workoutContext.isEmpty()) appendLine("none") else workoutContext.forEach(::appendLine)
        if (personalContext.isNotBlank()) appendLine(personalContext)
        appendLine("RULES: BIA is context, not diagnosis. Do not claim BIA proves protein deficiency or dehydration. Protein powder may be used even in NORMAL mode only when useful to meet the authoritative protein target or for practical meal composition. Creatine may be suggested only in SPORT mode. Creatine contributes 0 kcal/macros. Protein powder calories/macros count toward daily totals. hydrationNote may prudently encourage hydration when BIA/context supports attention, without diagnosing dehydration. Prefer ordinary foods first; supplements are optional tools, not mandatory. Count oils, dressings, caloric drinks and every caloric supplement. No punitive compensation.")
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are MyFitAI Nutrition Agent. Return only schema JSON. Work exclusively at nutritional level. Build a complete 7-day plan within ±3% of the app's authoritative kcal and macro targets. Use BIA/body measurements only as descriptive context: never diagnose protein deficiency, dehydration or disease from BIA. Protein powder is permitted when it helps meet protein targets or practical meal timing, including non-sport profiles; its full kcal/macros must be counted. Creatine is permitted only when SPORT_MODE=SPORT and must be represented separately with zero kcal/macros. Prefer ordinary foods first. Keep supplements separate from meals. Hydration guidance must be cautious and factual. The app independently validates totals and supplement rules."""
    }
}
