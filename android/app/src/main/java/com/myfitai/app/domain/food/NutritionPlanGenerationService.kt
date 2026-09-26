package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiUsageMetadata
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.repository.CalorieRecoveryRepository
import com.myfitai.app.data.repository.CheatEntryRepository
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
import com.myfitai.app.domain.ai.AiUserContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class NutritionPlanGenerationService(
    private val aiRuntime: AiRuntimeGateway,
    private val calculations: ProfileCalculationService,
    private val profiles: UserProfileRepository,
    private val workouts: WorkoutRepository,
    private val plans: MealPlanRepository,
    private val cheats: CheatEntryRepository,
    private val recovery: CalorieRecoveryRepository,
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
        val profileId = activeProfileStore.currentIdOrNull()
            ?: throw GenerationException.NeedsInput(listOf("profilo attivo"))
        return generateWeek(profileId, weekStart)
    }

    suspend fun generateWeek(profileId: Long, weekStart: LocalDate): Result {
        val monday = weekStart.minusDays((weekStart.dayOfWeek.value - 1).toLong())
        val today = time.today()
        if (monday.plusDays(6).isBefore(today)) throw GenerationException.PastWeek()
        val generationStart = if (today.isAfter(monday)) today else monday

        val profile = profiles.get(profileId)
            ?: throw GenerationException.NeedsInput(listOf("profilo"))
        val snapshot = calculations.profileSnapshot(profileId, time.today())
            ?: throw GenerationException.NeedsInput(listOf("dati profilo"))

        val calc = snapshot.calculation
        val existingPlan = plans.loadLatestSnapshot(profileId, monday.toEpochDay())
        val existingTargets = existingPlan?.version
        val goal = ProfileCalculationMapper.goal(profile.goal)
        val resolvedTargetKcal = calc.targetKcal ?: existingTargets?.targetKcal?.toDouble()
        val resolvedProteinG = calc.proteinG ?: existingTargets?.targetProteinG?.toDouble()
        val resolvedCarbsG = calc.carbsG ?: existingTargets?.targetCarbsG?.toDouble()
        val resolvedFatG = calc.fatG ?: existingTargets?.targetFatG?.toDouble()
        val missing = buildList {
            if (goal == null) add("obiettivo")
            if (resolvedTargetKcal == null) add("target calorie")
            if (resolvedProteinG == null) add("proteine")
            if (resolvedCarbsG == null) add("carboidrati")
            if (resolvedFatG == null) add("grassi")
            if (snapshot.latestWeightKg == null) add("peso")
        }
        if (missing.isNotEmpty()) throw GenerationException.NeedsInput(missing)

        val resolvedGoal = goal!!
        val weightKg = snapshot.latestWeightKg!!.toDouble()
        val adaptive = AdaptiveNutritionTargetEngine.adjust(
            AdaptiveNutritionTargetEngine.Input(
                goal = resolvedGoal,
                tdeeKcal = calc.tdeeKcal,
                baseTargetKcal = resolvedTargetKcal,
                currentWeightKg = weightKg,
                weight = snapshot.biaMetrics.weight.toAdaptiveEvidence(),
                bodyFat = snapshot.biaMetrics.bodyFat.toAdaptiveEvidence(),
                muscleMass = snapshot.biaMetrics.muscleMass.toAdaptiveEvidence(),
                waist = snapshot.bodyMetrics.waist.toAdaptiveEvidence(),
                abdomen = snapshot.bodyMetrics.abdomen.toAdaptiveEvidence(),
            )
        )
        val finalTargetKcal = adaptive.targetKcal ?: resolvedTargetKcal!!
        val finalMacros = LocalCalculationEngine.calculateMacrosForTarget(
            targetKcal = finalTargetKcal,
            weightKg = weightKg,
            goal = resolvedGoal,
        )
        val baseTargets = NutritionBusinessValidator.Targets(
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
        val dietaryProfile = DietaryProfile.parse(profile.dietaryPreferencesJson)

        val recoveryFrom = time.today().minusDays(CalorieRecoveryEngine.WINDOW_DAYS)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val recentCheats = cheats.between(profileId, recoveryFrom, time.nowEpochMillis()).first()
        val credits = buildList {
            for (entry in recentCheats) {
                val totalKcal = entry.estimatedKcal?.takeIf { it > 0 } ?: continue
                if (recovery.wasCheatAdapted(profileId, entry.id)) continue
                val alreadyAllocated = recovery.plannedRecoveryKcalOutsideWeek(
                    profileId = profileId,
                    cheatId = entry.id,
                    currentWeekStartEpochDay = monday.toEpochDay(),
                )
                val remainingKcal = (totalKcal - alreadyAllocated).coerceAtLeast(0)
                if (remainingKcal == 0) continue
                val occurredOn = Instant.ofEpochMilli(entry.occurredAtEpochMillis).atZone(zone).toLocalDate()
                add(CalorieRecoveryEngine.Credit(entry.id, occurredOn, remainingKcal))
            }
        }
        val recoveryPlan = CalorieRecoveryEngine.plan(
            monday = monday,
            today = time.today(),
            baseTargets = baseTargets,
            weightKg = weightKg,
            goal = resolvedGoal,
            credits = credits,
        )
        val dailyTargets = recoveryPlan.days
            .filter { !it.date.isBefore(generationStart) }
            .associate { it.date.toEpochDay() to it.targets }
        // The same app-authoritative ±3% tolerance applies to every goal.
        val targetTolerance = NutritionBusinessValidator.DEFAULT_TOLERANCE
        val maintenanceCeilingKcal = calc.tdeeKcal?.takeIf {
            resolvedGoal == LocalCalculationEngine.Goal.WEIGHT_LOSS ||
                resolvedGoal == LocalCalculationEngine.Goal.RECOMPOSITION
        }

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildUserPrompt(
                monday = monday,
                generationStart = generationStart,
                profile = profile,
                baseTargets = baseTargets,
                dailyTargets = dailyTargets,
                targetTolerance = targetTolerance,
                maintenanceCeilingKcal = maintenanceCeilingKcal,
                dietaryProfile = dietaryProfile,
                recoveryPlan = recoveryPlan,
                workoutContext = weekWorkouts.map { w ->
                    val dt = Instant.ofEpochMilli(w.startedAtEpochMillis).atZone(zone)
                    "${dt.toLocalDate()}@${dt.toLocalTime()}@${w.type}@${w.title}@${w.durationMinutes ?: 0}@${if (w.isRestDay) 1 else 0}"
                },
                personalContext = personalContext,
                snapshot = snapshot,
                sportsMode = sportsMode,
                mealsPerDay = mealsPerDay,
            ),
            schemaName = NutritionPlanCompactContract.SCHEMA_NAME,
            schemaJson = NutritionPlanCompactContract.schemaJson,
            // Number of generated days/meals varies; avoid a fixed output cap for plans.
            // Provider/model hard limits still apply; truncated output is rejected.
            maxOutputTokens = null,
            thinkingBudget = 0,
            allowSchemaFallback = true,
        )

        var parsed: NutritionPlanContract.Response? = null
        var attemptIndex = 0
        var acceptedTolerance = targetTolerance
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 2,
            businessValidator = { json ->
                runCatching {
                    val currentAttempt = attemptIndex++
                    val response = NutritionPlanCompactContract.parseEnvelope(json, mealsPerDay)
                    NutritionPlanContract.validateBusiness(
                        response = response,
                        expectedWeekStart = monday,
                        targets = baseTargets,
                        sportsMode = sportsMode,
                        enforceWeeklyVariety = true,
                        mealsPerDay = mealsPerDay,
                        // Validate each day against its actual app-computed recovery/target context.
                        dailyTargets = dailyTargets,
                        dietaryProfile = dietaryProfile,
                        tolerance = targetTolerance,
                        targetBelowOnly = false,
                        expectedFirstDate = generationStart,
                        maintenanceCeilingKcal = maintenanceCeilingKcal,
                    ).getOrThrow()
                    // The model must center the real M+S calorie sum on each daily target.
                    // Use the official ±3% guardrail only on the final business retry.
                    if (currentAttempt < 2) {
                        validatePreferredCalorieCentering(response, baseTargets, dailyTargets).getOrThrow()
                    }
                    // L'integrità nutrizionale è informativa (persistita in appValidation), non blocca:
                    // il gate autorevole resta validateBusiness (struttura, target ±3%, totali giorno).
                    acceptedTolerance = targetTolerance
                    parsed = response
                }
            },
        )
        val response = parsed ?: runCatching {
            NutritionPlanCompactContract.parseEnvelope(validated.jsonText, mealsPerDay).also {
                NutritionPlanContract.validateBusiness(
                    response = it,
                    expectedWeekStart = monday,
                    targets = baseTargets,
                    sportsMode = sportsMode,
                    enforceWeeklyVariety = true,
                    mealsPerDay = mealsPerDay,
                    dailyTargets = dailyTargets,
                    dietaryProfile = dietaryProfile,
                    tolerance = targetTolerance,
                    targetBelowOnly = false,
                    expectedFirstDate = generationStart,
                    maintenanceCeilingKcal = maintenanceCeilingKcal,
                ).getOrThrow()
            }
        }.getOrElse { throw GenerationException.InvalidAiOutput(it.message ?: "INVALID_COMPACT_PROTOCOL") }

        val integrity = NutritionIntegrityValidator.validate(
            response = response,
            targets = baseTargets,
            mealsPerDay = mealsPerDay,
            dailyTargets = dailyTargets,
            tolerance = acceptedTolerance,
            belowOnly = false,
        )

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
                    targets = baseTargets,
                    goal = profile.goal,
                    activityLevel = profile.activityLevel,
                    wakeTimeMinutes = profile.wakeTimeMinutes,
                    sleepTimeMinutes = profile.sleepTimeMinutes,
                    dietaryPreferences = dietaryProfile.toPromptCompact(),
                    sportsMode = sportsMode,
                    snapshot = snapshot,
                    userContext = AiUserContext.profileLine(profile, time.today(), snapshot.latestWeightKg),
                    calculationContext = AiUserContext.calculationLine(snapshot.calculation),
                    workouts = weekWorkouts.map { w ->
                        val dt = Instant.ofEpochMilli(w.startedAtEpochMillis).atZone(zone)
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

        val persistedTargets = averageTargets(dailyTargets.values.toList(), baseTargets)
        val dailyTargetsByEpochDay = recoveryPlan.days.associate { it.date.toEpochDay() to it.targets }
        val recoveryTokens = recoveryPlan.plannedBySource.entries
            .sortedBy { it.key }
            .joinToString(",") { (sourceId, kcal) -> CalorieRecoveryRepository.recoveryToken(sourceId, kcal) }
        val recoveryReason = if (recoveryPlan.plannedRecoveryKcal > 0) {
            ":RECOVERY=${recoveryPlan.plannedRecoveryKcal}:$recoveryTokens"
        } else ""
        val draft = PlanVersionDraft(
            source = validated.provider.name,
            reason = "AI_GENERATION:${adaptive.decision.name}:${adaptive.reasonCode}:${adaptive.evidenceWindowDays}D$recoveryReason",
            targetKcal = persistedTargets.kcal.toInt(),
            targetProteinG = persistedTargets.proteinG.toFloat(),
            targetCarbsG = persistedTargets.carbsG.toFloat(),
            targetFatG = persistedTargets.fatG.toFloat(),
            appValidationJson = integrity.toJson().toString(),
            days = response.days.sortedBy { it.dateEpochDay }.map { day ->
                val dayTargets = dailyTargetsByEpochDay[day.dateEpochDay] ?: baseTargets
                DayDraft(
                    dateEpochDay = day.dateEpochDay,
                    totalKcal = day.totalKcal,
                    proteinG = day.proteinG,
                    carbsG = day.carbsG,
                    fatG = day.fatG,
                    targetKcal = dayTargets.kcal.toInt(),
                    targetProteinG = dayTargets.proteinG.toFloat(),
                    targetCarbsG = dayTargets.carbsG.toFloat(),
                    targetFatG = dayTargets.fatG.toFloat(),
                    baseTargetKcal = adaptive.baseTargetKcal?.toInt(),
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
        generationStart: LocalDate,
        profile: com.myfitai.app.data.local.entity.UserProfileEntity,
        baseTargets: NutritionBusinessValidator.Targets,
        dailyTargets: Map<Long, NutritionBusinessValidator.Targets>,
        targetTolerance: Double,
        maintenanceCeilingKcal: Double?,
        dietaryProfile: DietaryProfile,
        recoveryPlan: CalorieRecoveryEngine.Result,
        workoutContext: List<String>,
        personalContext: String,
        snapshot: ProfileCalculationService.Snapshot,
        sportsMode: SportsNutritionClassifier.Mode,
        mealsPerDay: Int,
    ): String = buildString {
        appendLine("W:${monday.toEpochDay()}")
        appendLine("E:${maintenanceCeilingKcal?.toInt() ?: "?"}")
        appendLine("T:${baseTargets.kcal.toInt()}|${fmt(baseTargets.proteinG)}|${fmt(baseTargets.carbsG)}|${fmt(baseTargets.fatG)}|${(targetTolerance * 100).toInt()}")
        dailyTargets.toSortedMap().forEach { (day, target) ->
            appendLine("TD:$day|${target.kcal.toInt()}|${fmt(target.proteinG)}|${fmt(target.carbsG)}|${fmt(target.fatG)}|${(targetTolerance * 100).toInt()}")
        }
        appendLine("TARGET_POLICY:Center each daily TD kcal and macro target; aim within 1% where practical; the supplied 3% is a hard validation margin, not an extra calorie allowance. Do not aim systematically at TD+3% or subtract a second deficit.")
        appendLine("REC:${recoveryPlan.availableBeforeKcal}|${recoveryPlan.plannedRecoveryKcal}|${recoveryPlan.remainingKcal}|10")
        appendLine("MEALS_PER_DAY:$mealsPerDay")
        appendLine("GENERATE_FROM:${generationStart.toEpochDay()}|${monday.plusDays(6).toEpochDay()}")
        appendLine("SM:${sportsMode.name}")
        appendLine("U:${AiUserContext.profileLine(profile, time.today(), snapshot.latestWeightKg)}")
        appendLine("LC:${AiUserContext.calculationLine(snapshot.calculation)}")
        appendLine("P:${compact(profile.goal)}|${compact(profile.activityLevel)}|${profile.wakeTimeMinutes ?: "?"}|${profile.sleepTimeMinutes ?: "?"}")
        appendLine("DP:${dietaryProfile.toPromptCompact()}")

        val b = snapshot.biaMetrics
        appendLine("B0:${values(b.weight.baseline, b.bodyFat.baseline, b.muscleMass.baseline, b.skeletalMuscle.baseline, b.bodyWater.baseline, b.visceralFat.baseline)}")
        appendLine("B:${values(b.weight.current, b.bodyFat.current, b.muscleMass.current, b.skeletalMuscle.current, b.bodyWater.current, b.visceralFat.current)}")
        appendLine("BT:${values(b.weight.recentTrend.delta, b.bodyFat.recentTrend.delta, b.muscleMass.recentTrend.delta, b.skeletalMuscle.recentTrend.delta, b.bodyWater.recentTrend.delta, b.visceralFat.recentTrend.delta)}")
        appendLine("BIA_ALL:${values(b.bmr.current, b.fatMass.current, b.leanMass.current, b.bodyWaterKg.current, b.subcutaneousFat.current, b.boneMass.current, b.proteinPercent.current, b.proteinKg.current, b.bodyAge.current, b.bmi.current)}")

        val bm = snapshot.bodyMetrics
        appendLine("BM0:${bodyValues(bm) { it.baseline }}")
        appendLine("BM:${bodyValues(bm) { it.current }}")
        appendLine("BMD:${bodyValues(bm) { it.previousDelta }}")
        appendLine("BMT:${bodyValuesDouble(bm) { it.recentTrend.delta }}")
        appendLine("RS:${snapshot.recompositionState.name}")

        workoutContext.forEach { appendLine("WO:${compact(it)}") }
        if (personalContext.isNotBlank()) appendLine("PC:${compact(personalContext)}")
    }

    private fun averageTargets(
        values: List<NutritionBusinessValidator.Targets>,
        fallback: NutritionBusinessValidator.Targets,
    ): NutritionBusinessValidator.Targets {
        if (values.isEmpty()) return fallback
        return NutritionBusinessValidator.Targets(
            kcal = values.map { it.kcal }.average(),
            proteinG = values.map { it.proteinG }.average(),
            carbsG = values.map { it.carbsG }.average(),
            fatG = values.map { it.fatG }.average(),
        )
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
        body.chest, body.waist, body.abdomen, body.shoulders, body.glutes, body.hips,
        body.armLeft, body.armRight, body.thighLeft, body.thighRight, body.calfLeft, body.calfRight,
    ).joinToString("|") { fmtOrUnknown(pick(it)) }

    private fun bodyValuesDouble(
        body: ProfileCalculationService.BodyMeasurementsSnapshot,
        pick: (ProfileCalculationService.MetricSnapshot) -> Double?,
    ): String = listOf(
        body.chest, body.waist, body.abdomen, body.shoulders, body.glutes, body.hips,
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
        private const val PREFERRED_KCAL_TOLERANCE = 0.01

        internal fun validatePreferredCalorieCentering(
            response: NutritionPlanContract.Response,
            baseTargets: NutritionBusinessValidator.Targets,
            dailyTargets: Map<Long, NutritionBusinessValidator.Targets>,
        ): kotlin.Result<Unit> = runCatching {
            response.days.forEach { day ->
                val target = (dailyTargets[day.dateEpochDay] ?: baseTargets).kcal
                require(target > 0.0) { "CALORIE_TARGET_INVALID" }
                val deviation = kotlin.math.abs(day.totalKcal - target) / target
                require(deviation <= PREFERRED_KCAL_TOLERANCE + 1e-9) {
                    "CALORIE_TARGET_NOT_CENTERED:${day.dateEpochDay}:target=${target.toInt()}:actual=${day.totalKcal}:preferred=1%"
                }
            }
        }

        private val SYSTEM_PROMPT = """
MyFitAI NutritionPlanAgent. Your ONLY operational responsibility is generating a complete weekly nutrition plan from the authoritative targets and context supplied by the app. Never choose/change the user's goal, interpret progress, or adapt a recorded deviation/cheat; dedicated agents own those tasks. Output ONLY JSON matching the supplied envelope schema. The `data` string must begin with the exact line `MFP1`, followed by the pipe records below. Do not omit `MFP1`, do not replace it with another header, do not use markdown, and do not add text outside records.
${AiUserContext.INPUT_DESCRIPTION}
${NutritionPlanCompactContract.PROTOCOL}
E is the estimated maintenance expenditure (TDEE), or ? for a goal without a mandatory deficit. It is NOT the diet target: T and each TD are already adjusted for the user's chosen goal. If E is numeric, ensure the sum of meals plus caloric supplements is strictly BELOW E for every generated day; never fill all maintenance calories merely because the target tolerance permits it. T is the base local target. Every TD line is the AUTHORITATIVE target for that specific epoch day and overrides T for that day. CENTER the actual daily sum of meals and caloric supplements on that day's TD kcal AND protein/carbs/fat targets, ideally within 1% where practicable. The ±3% in T/TD is ONLY the app's outer acceptance margin for rounding and food composition, NOT bonus calories or a range to saturate. Do not systematically aim at its upper bound. The goal-specific deficit or surplus is already included in TD: never apply a second calorie adjustment. REC is informational only: available|planned|remaining|maxDailyPercent. Never calculate, increase or decrease recovery yourself and never compensate beyond TD. B0/B/BT order is weightKg|bodyFatPct|muscleMassKg|skeletalMuscleKg|bodyWaterPct|visceralFat and means baseline/current/recent-trend-delta. BM0/BM/BMD/BMT order is chest|waist|abdomen|shoulders|glutes|hips|armLeft|armRight|thighLeft|thighRight|calfLeft|calfRight and means baseline/current/previous-delta/recent-trend-delta. `?` means unavailable. Body/BIA signals are contextual only: use them jointly to inform food choice, distribution and timing, never to autonomously alter calories/macros, diagnose disease, dehydration, edema or muscle loss, or infer causality from one reading. Weight alone must never drive a dietary change.
DP format is A=allergies;I=intolerances;E=excludedFoods;D=dislikedFoods;P=preferredFoods;S=dietStyle;N=free-text food preferences. A, I, E and S are HARD constraints: never output an ingredient that violates them. D, P and N are SOFT preferences only. Read them and follow them when possible, but never change, stretch or bypass the authoritative calorie and macro targets to satisfy them. Requests in N, including quantities, frequency goals and weekly objectives such as "pizza once per week" or "gelato twice per week", are suggestions rather than mandatory requirements. Include or distribute them only when they fit naturally within the daily TD targets; otherwise reduce, replace or omit them and keep the targets exact. Do not weaken, reinterpret or override hard constraints. The app independently validates every ingredient and rejects violations.
Rules: generate exactly the dates in GENERATE_FROM (inclusive) through its end date, with exactly MEALS_PER_DAY meals per day. The complete record order is W, then for each requested day exactly one D followed by its M records, each meal's I records, and optional S/H records; after the final requested day emit exactly ONE V record as the final line. Never emit V inside a day or more than once. Use distinct meal slots with practical timing unless the supplied schedule requires different names. Never use `|` or line breaks inside a text field. All kcal/macros are numeric. D totals are transport hints only: the app recalculates authoritative daily kcal/protein/carbs/fat from all M records plus caloric S records. Therefore calculate the SUM of M+S values before emitting each D, refine portions to match that day's exact TD kcal and macros as closely as possible, and use the supplied tolerance only as a last-resort acceptance bound; do not rely on D values to satisfy the target. For every meal/supplement, kcal must remain coherent with 4*proteinG + 4*carbsG + 9*fatG within the app integrity tolerance. Count oils, dressings and caloric drinks. Ordinary foods first. Protein powder is optional and its kcal/macros count. Creatine only when SM=SPORT and always 0 kcal/P/C/F. H may give cautious hydration guidance. No punitive compensation. V notes <= 8 words. Skeleton: MFP1 -> W -> requested dates (D -> M/I/S/H) -> V exactly once.
VARIETY: make every meal recipe different across the requested dates. Rotate protein sources, vegetables, fruit, grains and preparation methods. Do not repeat the same meal title with the same ingredient set on another day. Recurring staples such as oil, salt, spices or water are allowed; the complete recipe must not be duplicated.
""".trimIndent()
    }
}
