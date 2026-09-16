package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiUsageMetadata
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.data.repository.SupplementDraft
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.calculation.AdaptiveNutritionTargetEngine
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
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
    private val mealCountPreferences: MealCountPreferences? = null,
    private val planReview: PlanReviewService? = null,
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
        val goal = ProfileCalculationMapper.goal(profile.goal)
        val missing = buildList {
            if (goal == null) add("obiettivo")
            if (calc.targetKcal == null) add("target calorie")
            if (calc.proteinG == null) add("proteine")
            if (calc.carbsG == null) add("carboidrati")
            if (calc.fatG == null) add("grassi")
            if (snapshot.latestWeightKg == null) add("peso")
        }
        if (missing.isNotEmpty()) throw GenerationException.NeedsInput(missing)

        val adaptive = AdaptiveNutritionTargetEngine.adjust(
            AdaptiveNutritionTargetEngine.Input(
                goal = goal!!,
                tdeeKcal = calc.tdeeKcal,
                baseTargetKcal = calc.targetKcal,
                currentWeightKg = snapshot.latestWeightKg!!.toDouble(),
                weight = snapshot.biaMetrics.weight.toAdaptiveEvidence(),
                bodyFat = snapshot.biaMetrics.bodyFat.toAdaptiveEvidence(),
                muscleMass = snapshot.biaMetrics.muscleMass.toAdaptiveEvidence(),
                waist = snapshot.bodyMetrics.waist.toAdaptiveEvidence(),
                abdomen = snapshot.bodyMetrics.abdomen.toAdaptiveEvidence(),
            )
        )
        val finalTargetKcal = adaptive.targetKcal ?: calc.targetKcal!!
        val finalMacros = LocalCalculationEngine.calculateMacrosForTarget(
            targetKcal = finalTargetKcal,
            weightKg = snapshot.latestWeightKg!!.toDouble(),
            goal = goal,
        )
        val targets = NutritionBusinessValidator.Targets(
            kcal = finalTargetKcal,
            proteinG = finalMacros.proteinG,
            carbsG = finalMacros.carbsG,
            fatG = finalMacros.fatG,
        )

        val zone = time.zoneId
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val weekWorkouts = workouts.between(profileId, from, to).first()
        val sportsMode = SportsNutritionClassifier.classify(profile.activityLevel, weekWorkouts)
        val mealsPerDay = mealCountPreferences?.get(profileId) ?: MealCountPreferences.DEFAULT
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
                mealsPerDay = mealsPerDay,
            ),
            schemaName = NutritionPlanCompactContract.SCHEMA_NAME,
            schemaJson = NutritionPlanCompactContract.schemaJson,
            maxOutputTokens = 6_000,
            thinkingBudget = 0,
            // Gemini models can reject the full native schema; retry the same request as
            // JSON-only and keep canonical parsing/business validation local and authoritative.
            allowSchemaFallback = true,
        )

        var parsed: NutritionPlanContract.Response? = null
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 1,
            businessValidator = { json ->
                runCatching {
                    val response = NutritionPlanCompactContract.parseEnvelope(json, mealsPerDay)
                    NutritionPlanContract.validateBusiness(
                        response,
                        monday,
                        targets,
                        sportsMode,
                        enforceWeeklyVariety = true,
                        mealsPerDay = mealsPerDay,
                    ).getOrThrow()
                    parsed = response
                }
            },
        )
        val response = parsed ?: runCatching { NutritionPlanCompactContract.parseEnvelope(validated.jsonText, mealsPerDay) }
            .getOrElse { throw GenerationException.InvalidAiOutput("INVALID_COMPACT_PROTOCOL") }

        val existing = plans.getPlanForWeek(profileId, monday.toEpochDay())
        val reviewReason = if (existing == null) {
            PlanReviewPolicy.Reason.NEW_WEEKLY_PLAN
        } else {
            PlanReviewPolicy.Reason.FULL_REGENERATION
        }
        if (planReview != null && PlanReviewPolicy.shouldReview(reviewReason)) {
            val review = planReview.review(
                plan = response,
                context = PlanReviewService.Context(
                    monday = monday,
                    targets = targets,
                    goal = profile.goal,
                    activityLevel = profile.activityLevel,
                    wakeTimeMinutes = profile.wakeTimeMinutes,
                    sleepTimeMinutes = profile.sleepTimeMinutes,
                    dietaryPreferences = profile.dietaryPreferencesJson,
                    sportsMode = sportsMode,
                    snapshot = snapshot,
                    workouts = weekWorkouts.map { w ->
                        val dt = java.time.Instant.ofEpochMilli(w.startedAtEpochMillis).atZone(zone)
                        PlanReviewService.WorkoutSignal(
                            dayOffset = (dt.toLocalDate().toEpochDay() - monday.toEpochDay()).toInt().coerceIn(0, 6),
                            timeMinutes = dt.toLocalTime().hour * 60 + dt.toLocalTime().minute,
                            type = w.type,
                            durationMinutes = w.durationMinutes ?: 0,
                            isRestDay = w.isRestDay,
                        )
                    },
                ),
            )
            if (!review.accepted) {
                val issueCodes = review.issues
                    .filter { it.severity.blocking }
                    .joinToString(",") { it.code.code }
                    .ifBlank { "NONE" }
                throw GenerationException.InvalidAiOutput("PLAN_REVIEW_${review.status.name}:$issueCodes")
            }
        }

        val draft = PlanVersionDraft(
            source = validated.provider.name,
            reason = "AI_GENERATION:${adaptive.decision.name}:${adaptive.reasonCode}:${adaptive.evidenceWindowDays}D",
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
        mealsPerDay: Int,
    ): String = buildString {
        appendLine("W:${monday.toEpochDay()}")
        appendLine("T:${targets.kcal.toInt()}|${fmt(targets.proteinG)}|${fmt(targets.carbsG)}|${fmt(targets.fatG)}|3")
        appendLine("MEALS_PER_DAY:$mealsPerDay")
        appendLine("SM:${sportsMode.name}")
        appendLine("P:${compact(profile.goal)}|${compact(profile.activityLevel)}|${profile.wakeTimeMinutes ?: "?"}|${profile.sleepTimeMinutes ?: "?"}")
        appendLine("DP:${compact(profile.dietaryPreferencesJson)}")

        val b = snapshot.biaMetrics
        appendLine("B0:${values(b.weight.baseline, b.bodyFat.baseline, b.muscleMass.baseline, b.skeletalMuscle.baseline, b.bodyWater.baseline, b.visceralFat.baseline)}")
        appendLine("B:${values(b.weight.current, b.bodyFat.current, b.muscleMass.current, b.skeletalMuscle.current, b.bodyWater.current, b.visceralFat.current)}")
        appendLine("BT:${values(b.weight.recentTrend.delta, b.bodyFat.recentTrend.delta, b.muscleMass.recentTrend.delta, b.skeletalMuscle.recentTrend.delta, b.bodyWater.recentTrend.delta, b.visceralFat.recentTrend.delta)}")

        val bm = snapshot.bodyMetrics
        appendLine("BM0:${bodyValues(bm) { it.baseline }}")
        appendLine("BM:${bodyValues(bm) { it.current }}")
        appendLine("BMD:${bodyValues(bm) { it.previousDelta }}")
        appendLine("BMT:${bodyValuesDouble(bm) { it.recentTrend.delta }}")
        appendLine("RS:${snapshot.recompositionState.name}")

        workoutContext.forEach { appendLine("WO:${compact(it)}") }
        if (personalContext.isNotBlank()) appendLine("PC:${compact(personalContext)}")
    }

    private fun ProfileCalculationService.MetricSnapshot.toAdaptiveEvidence() = AdaptiveNutritionTargetEngine.Evidence(
        count = recentTrend.count,
        spanDays = recentSpanDays,
        delta = recentTrend.delta,
    )

    private fun bodyValues(
        body: ProfileCalculationService.BodyMeasurementsSnapshot,
        pick: (ProfileCalculationService.MetricSnapshot) -> Float?,
    ): String = listOf(
        body.chest, body.waist, body.abdomen, body.shoulders, body.glutes,
        body.armLeft, body.armRight, body.thighLeft, body.thighRight, body.calfLeft, body.calfRight,
    ).joinToString("|") { fmtOrUnknown(pick(it)) }

    private fun bodyValuesDouble(
        body: ProfileCalculationService.BodyMeasurementsSnapshot,
        pick: (ProfileCalculationService.MetricSnapshot) -> Double?,
    ): String = listOf(
        body.chest, body.waist, body.abdomen, body.shoulders, body.glutes,
        body.armLeft, body.armRight, body.thighLeft, body.thighRight, body.calfLeft, body.calfRight,
    ).joinToString("|") { fmtOrUnknown(pick(it)) }

    private fun values(vararg values: Float?): String = values.joinToString("|") { fmtOrUnknown(it) }
    private fun values(vararg values: Double?): String = values.joinToString("|") { fmtOrUnknown(it) }

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
Input context: T=kcal|protein|carbs|fat|tolerance and is AUTHORITATIVE. Never recalculate or override T from body data. B0/B/BT order is weightKg|bodyFatPct|muscleMassKg|skeletalMuscleKg|bodyWaterPct|visceralFat and means baseline/current/recent-trend-delta. BM0/BM/BMD/BMT order is chest|waist|abdomen|shoulders|glutes|armLeft|armRight|thighLeft|thighRight|calfLeft|calfRight and means baseline/current/previous-delta/recent-trend-delta. `?` means unavailable. Body/BIA signals are contextual only: use them jointly to inform food choice, distribution and timing, never to autonomously alter calories/macros, diagnose disease, dehydration, edema or muscle loss, or infer causality from one reading. Weight alone must never drive a dietary change.
 Rules: exactly 7 days and exactly MEALS_PER_DAY meals per day. The complete record order is W, then for each day exactly one D followed by its M records, each meal's I records, and optional S/H records; after all 7 days emit exactly ONE V record as the final line. Never emit V inside a day or more than once. Use distinct meal slots with practical timing unless the supplied schedule requires different names. Never use `|` or line breaks inside a text field. All kcal/macros are numeric. Daily totals include meals plus caloric supplements and must be within ±3% of authoritative targets. Count oils, dressings and caloric drinks. Ordinary foods first. Protein powder is optional and its kcal/macros count. Creatine only when SM=SPORT and always 0 kcal/P/C/F. H may give cautious hydration guidance. No punitive compensation. V notes <= 8 words. Skeleton: MFP1 -> W -> (D -> M/I/S/H repeated for 7 days) -> V exactly once.
VARIETY: make every meal recipe different across the seven days. Rotate protein sources, vegetables, fruit, grains and preparation methods. Do not repeat the same meal title with the same ingredient set on another day. Recurring staples such as oil, salt, spices or water are allowed; the complete recipe must not be duplicated.
""".trimIndent()
    }
}
