package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiUsageMetadata
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
        val usage: AiUsageMetadata? = null,
    )

    suspend fun generateWeek(weekStart: LocalDate): Result {
        val monday = weekStart.minusDays((weekStart.dayOfWeek.value - 1).toLong())
        if (monday.plusDays(6).isBefore(time.today())) throw GenerationException.PastWeek()

        val profileId = activeProfileStore.currentIdOrNull() ?: throw GenerationException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId) ?: throw GenerationException.NeedsInput(listOf("profilo"))
        val snapshot = calculations.activeProfileSnapshot(time.today()) ?: throw GenerationException.NeedsInput(listOf("dati profilo"))
        val missingBodyData = buildList {
            if (snapshot.latestBiaTimestamp == null) add("una rilevazione BIA")
            if (snapshot.latestBodyMeasurementTimestamp == null) add("una rilevazione di misure corporee")
        }
        if (missingBodyData.isNotEmpty()) throw GenerationException.NeedsInput(missingBodyData)
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
                    "${dt.toLocalDate()}@${dt.toLocalTime()}@${w.type}@${w.title}@${w.durationMinutes ?: 0}@${if (w.isRestDay) 1 else 0}"
                },
                personalContext = personalContext,
                snapshot = snapshot,
                sportsMode = sportsMode,
            ),
            schemaName = NutritionPlanCompactContract.SCHEMA_NAME,
            schemaJson = NutritionPlanCompactContract.schemaJson,
            maxOutputTokens = 6_000,
            thinkingBudget = 0,
        )

        var parsed: NutritionPlanContract.Response? = null
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 1,
            businessValidator = { json ->
                runCatching {
                    val response = NutritionPlanCompactContract.parseEnvelope(json)
                    NutritionPlanContract.validateBusiness(response, monday, targets, sportsMode, enforceWeeklyVariety = true).getOrThrow()
                    parsed = response
                }
            },
        )
        val response = parsed ?: runCatching { NutritionPlanCompactContract.parseEnvelope(validated.jsonText) }
            .getOrElse { throw GenerationException.InvalidAiOutput("INVALID_COMPACT_PROTOCOL") }

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
        return Result(planId, versionId, validated.provider.name, validated.model, response.agentValidation, validated.usage)
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
        appendLine("W:${monday.toEpochDay()}")
        appendLine("T:${targets.kcal.toInt()}|${fmt(targets.proteinG)}|${fmt(targets.carbsG)}|${fmt(targets.fatG)}|3")
        appendLine("SM:${sportsMode.name}")
        appendLine("P:${compact(profile.goal)}|${compact(profile.activityLevel)}|${profile.wakeTimeMinutes ?: "?"}|${profile.sleepTimeMinutes ?: "?"}")
        appendLine("DP:${compact(profile.dietaryPreferencesJson)}")
        appendLine("B:${fmtOrUnknown(snapshot.latestWeightKg)}|${fmtOrUnknown(snapshot.latestBodyFatPercent)}|${fmtOrUnknown(snapshot.latestMuscleMassKg)}|${fmtOrUnknown(snapshot.latestSkeletalMuscleKg)}|${fmtOrUnknown(snapshot.latestBodyWaterPercent)}|${fmtOrUnknown(snapshot.latestWaistCm)}")
        appendLine("TR:${fmtOrUnknown(snapshot.weightTrend.delta)}|${fmtOrUnknown(snapshot.bodyFatTrend.delta)}|${fmtOrUnknown(snapshot.muscleMassTrend.delta)}|${fmtOrUnknown(snapshot.waistTrend.delta)}|${compact(snapshot.recompositionState.toString())}")
        workoutContext.forEach { appendLine("WO:${compact(it)}") }
    }

    private fun compact(value: String?): String = value.orEmpty()
        .replace('|', '/')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .ifBlank { "?" }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun fmtOrUnknown(value: Double?): String = value?.let(::fmt) ?: "?"
    private fun fmtOrUnknown(value: Float?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "?"

    companion object {
        private val SYSTEM_PROMPT = """
MyFitAI nutrition planner. Output ONLY JSON matching the supplied envelope schema. The `data` string must begin with the exact line `MFP1`, followed by the pipe records below. Do not omit `MFP1`, do not replace it with another header, do not use markdown, and do not add text outside records.
${NutritionPlanCompactContract.PROTOCOL}
Rules: exactly 7 days; records ordered W, then each D with its M/I and optional S/H, then V. Never use `|` or line breaks inside a text field. All kcal/macros are numeric. Daily totals include meals plus caloric supplements and must be within ±3% of authoritative targets. Count oils, dressings and caloric drinks. Ordinary foods first. Protein powder is optional and its kcal/macros count. Creatine only when SM=SPORT and always 0 kcal/P/C/F. BIA is descriptive context only: no diagnosis of protein deficiency, dehydration or disease. H may give cautious hydration guidance. No punitive compensation. V notes <= 8 words.
VARIETY: make every meal recipe different across the seven days. Rotate protein sources, vegetables, fruit, grains and preparation methods. Do not repeat the same meal title with the same ingredient set on another day. Recurring staples such as oil, salt, spices or water are allowed; the complete recipe must not be duplicated.
""".trimIndent()
    }
}
