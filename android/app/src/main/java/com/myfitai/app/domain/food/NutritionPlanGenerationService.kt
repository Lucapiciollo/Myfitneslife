package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.personalization.PersonalResponseService
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class NutritionPlanGenerationService(
    private val aiRuntime: AiRuntimeService,
    private val calculations: ProfileCalculationService,
    private val profiles: UserProfileRepository,
    private val workouts: WorkoutRepository,
    private val plans: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val personalResponse: PersonalResponseService,
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
        if (monday.plusDays(6).isBefore(LocalDate.now())) throw GenerationException.PastWeek()

        val profileId = activeProfileStore.currentIdOrNull() ?: throw GenerationException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId) ?: throw GenerationException.NeedsInput(listOf("profilo"))
        val snapshot = calculations.activeProfileSnapshot() ?: throw GenerationException.NeedsInput(listOf("dati profilo"))
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

        val zone = ZoneId.systemDefault()
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val weekWorkouts = workouts.between(profileId, from, to).first()
        val personalContext = personalResponse.promptContext()

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
            ),
            schemaName = NutritionPlanContract.SCHEMA_NAME,
            schemaJson = NutritionPlanContract.schemaJson,
            maxOutputTokens = 16_000,
        )

        var parsed: NutritionPlanContract.Response? = null
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 1,
            businessValidator = { json ->
                runCatching {
                    val response = NutritionPlanContract.parse(json)
                    NutritionPlanContract.validateBusiness(response, monday, targets).getOrThrow()
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
                )
            },
        )

        val existing = plans.getPlanForWeek(profileId, monday.toEpochDay())
        val planId = existing?.id ?: plans.createPlan(profileId, monday.toEpochDay(), System.currentTimeMillis())
        val versionId = plans.appendVersion(planId, System.currentTimeMillis(), draft)
        return Result(planId, versionId, validated.provider.name, validated.model, response.agentValidation)
    }

    private fun buildUserPrompt(
        monday: LocalDate,
        profile: com.myfitai.app.data.local.entity.UserProfileEntity,
        targets: NutritionBusinessValidator.Targets,
        workoutContext: List<String>,
        personalContext: String,
    ): String = buildString {
        appendLine("Generate the nutrition plan for the week starting ${monday.toEpochDay()} ($monday).")
        appendLine("The app-calculated DAILY targets are authoritative and must be respected within ±3% for every day:")
        appendLine("kcal=${targets.kcal.toInt()}, proteinG=${String.format(Locale.US, "%.1f", targets.proteinG)}, carbsG=${String.format(Locale.US, "%.1f", targets.carbsG)}, fatG=${String.format(Locale.US, "%.1f", targets.fatG)}")
        appendLine("Profile goal: ${profile.goal ?: "not specified"}")
        appendLine("Activity: ${profile.activityLevel ?: "not specified"}")
        appendLine("Wake minutes: ${profile.wakeTimeMinutes ?: "not specified"}; sleep minutes: ${profile.sleepTimeMinutes ?: "not specified"}")
        appendLine("Dietary preferences: ${profile.dietaryPreferencesJson ?: "none specified"}")
        appendLine("Planned workouts this week:")
        if (workoutContext.isEmpty()) appendLine("none") else workoutContext.forEach { appendLine(it) }
        if (personalContext.isNotBlank()) {
            appendLine()
            appendLine(personalContext)
        }
        appendLine("Use practical foods and explicit quantities. Count oils, dressings and caloric drinks. displayDose must be understandable to a person while quantity+unit remain numeric/structured.")
        appendLine("Use weightState to clarify raw/cooked/drained state where relevant and nutritionConfidence to express estimate quality.")
        appendLine("Prefer seasonal variety when compatible with preferences and targets. Do not invent allergies, intolerances or medical diagnoses.")
        appendLine("Do not use punitive compensation. Timing around training may be adjusted prudently without changing the daily authoritative targets.")
        appendLine("Historical patterns are descriptive context only: never treat them as causal and never use them to override app-calculated targets.")
        appendLine("agentValidation is advisory only; the app will independently validate all totals.")
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are MyFitAI Nutrition Agent. Return only JSON conforming exactly to the supplied schema. The app is the authority for numerical targets. Build a complete 7-day plan, each day within ±3% of kcal, protein, carbohydrates and fat targets. Daily totals must also agree with the sum of meal totals within ±3%. Every ingredient requires a positive numeric quantity, unit, displayDose, weightState, nutritionConfidence and category. Count condiments and caloric beverages. Personal-history observations are associative/descriptive only and must never be treated as causal evidence or used to override local targets. Be factual and cautious; do not make medical claims."""
    }
}
