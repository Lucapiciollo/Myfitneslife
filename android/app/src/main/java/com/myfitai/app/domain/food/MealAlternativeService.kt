package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.toDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.food.catalog.FoodCatalogLoader
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.data.repository.SupplementDraft
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.calculation.SafetyCalorieFloor
import com.myfitai.app.domain.profile.ProfileAgeRule
import com.myfitai.app.domain.safety.GenerationSafetyGate
import com.myfitai.app.domain.safety.HealthDeclarationCodec
import com.myfitai.app.domain.safety.HealthDeclarationDecodeResult
import com.myfitai.app.domain.safety.HealthDeclarationInput
import com.myfitai.app.domain.safety.SafetyDecision
import com.myfitai.app.domain.safety.SafetyInput
import com.myfitai.app.domain.safety.SafetyOutcome
import com.myfitai.app.domain.safety.SafetyThresholds
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Ephemeral AI alternative generator for one planned meal.
 * Suggestions are not persisted. Only an explicitly accepted alternative creates a new immutable plan version.
 */
class MealAlternativeService(
    private val aiRuntime: AiRuntimeGateway,
    private val profiles: UserProfileRepository,
    private val plans: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
    /** MFP2/MA2: catalogo alimenti e interruttore (stesso di NutritionPlanGenerationService); spento = solo flusso MA1. */
    private val foodCatalogLoader: FoodCatalogLoader? = null,
    private val useFoodCatalogPlan: () -> Boolean = { false },
    private val calculations: ProfileCalculationService,
) {
    data class Alternatives(
        val planId: Long,
        val sourceVersionId: Long,
        val weekStartEpochDay: Long,
        val dayEpochDay: Long,
        val mealId: Long,
        val mealType: String,
        val mealTimeMinutes: Int?,
        val targetKcal: Int,
        val items: List<MealAlternativeContract.Alternative>,
        val provider: String,
        val model: String,
    )

    data class ApplyResult(val versionId: Long, val title: String, val kcal: Int)

    data class HistoricalAlternative(
        val sourcePlanId: Long,
        val sourceWeekStartEpochDay: Long,
        val sourceVersionId: Long,
        val sourceVersionNumber: Int,
        val sourceDateEpochDay: Long,
        val targetPlanVersionId: Long,
        val targetMealId: Long,
        val targetKcal: Int,
        val targetType: String,
        val targetTimeMinutes: Int?,
        val meal: FoodMeal,
        val calorieDelta: Int,
    )

    private data class SafetyBaseline(
        val profile: UserProfileEntity,
        val input: SafetyInput,
        val decision: SafetyDecision,
        val inputsFingerprint: String,
        val latestBiaTimestamp: Long?,
        val tdeeKcal: Double?,
        val minimumDailyKcal: Double?,
        val maximumDailyDeficitKcal: Double?,
        val maintenanceOnly: Boolean,
    )

    sealed class AlternativeException(message: String) : Exception(message) {
        class NeedsInput(val fields: List<String>) : AlternativeException("NEEDS_INPUT: ${fields.joinToString()}")
        class PastMeal : AlternativeException("I pasti già trascorsi restano storico e non possono essere sostituiti.")
        class StalePlan : AlternativeException("Il piano è cambiato: genera di nuovo le alternative.")
        class InvalidAlternative : AlternativeException("L'alternativa non rispetta i vincoli del pasto o del profilo.")
        class SafetyBlocked(val decision: SafetyDecision) : AlternativeException("SAFETY_BLOCKED")
        class SafetyChanged : AlternativeException("SAFETY_PROFILE_CHANGED")
        /** Saved allergies/intolerances/exclusions cannot be read: alternatives are blocked instead of unchecked. */
        class ConstraintsUnreadable : AlternativeException(DietaryProfile.UNREADABLE_MESSAGE) {
            /** Stable machine code, never part of the user text. */
            val code: String = DietaryProfile.UNREADABLE_CODE
        }
    }

    suspend fun generate(weekStartEpochDay: Long, dayEpochDay: Long, mealId: Long): Alternatives {
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        return generate(profileId, weekStartEpochDay, dayEpochDay, mealId)
    }

    suspend fun generate(profileId: Long, weekStartEpochDay: Long, dayEpochDay: Long, mealId: Long): Alternatives {
        val profile = profiles.get(profileId) ?: throw AlternativeException.NeedsInput(listOf("profilo"))
        val dietaryProfile = readableDietaryProfile(profile)
        val snapshot = plans.loadLatestSnapshot(profileId, weekStartEpochDay) ?: throw AlternativeException.NeedsInput(listOf("piano alimentare"))
        val day = snapshot.version.days.firstOrNull { it.dateEpochDay == dayEpochDay } ?: throw AlternativeException.NeedsInput(listOf("giorno del piano"))
        val meal = day.meals.firstOrNull { it.id == mealId } ?: throw AlternativeException.NeedsInput(listOf("pasto"))
        val targetKcal = meal.kcal ?: throw AlternativeException.NeedsInput(listOf("calorie del pasto"))
        ensureNotPast(dayEpochDay, meal.timeMinutes)

        // Pasto COMPUTED + interruttore acceso: l'IA sceglie alimenti e grammi, l'app calcola (MA2). Altrimenti MA1 invariato.
        if (meal.nutritionSource == FoodMeal.NUTRITION_SOURCE_COMPUTED && useFoodCatalogPlan()) {
            foodCatalogLoader?.load()?.let { catalog ->
                val result = MealAlternativeCatalogEngine(catalog)
                    .generate(aiRuntime, MealAlternativeCatalogEngine.Input(weekStartEpochDay, dayEpochDay, meal, dietaryProfile))
                return Alternatives(snapshot.planId, snapshot.version.id, weekStartEpochDay, dayEpochDay, meal.id, meal.type, meal.timeMinutes, targetKcal, result.items, result.provider, result.model)
            }
        }

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(dietaryProfile, day, meal, targetKcal),
            schemaName = MealAlternativeContract.SCHEMA_NAME,
            schemaJson = MealAlternativeContract.schemaJson,
            maxOutputTokens = 4_000,
        )
        var parsed: MealAlternativeContract.Response? = null
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 1,
            businessValidator = { json -> runCatching {
                val response = MealAlternativeContract.parse(json)
                MealAlternativeContract.validateBusiness(response, meal).getOrThrow()
                response.alternatives.forEach { FoodConstraintValidator.validateAlternative(it, dietaryProfile).getOrThrow() }
                parsed = response
            } },
        )
        val response = parsed ?: MealAlternativeContract.parse(validated.jsonText).also {
            MealAlternativeContract.validateBusiness(it, meal).getOrThrow()
            it.alternatives.forEach { alternative ->
                FoodConstraintValidator.validateAlternative(alternative, dietaryProfile).getOrThrow()
            }
        }

        return Alternatives(snapshot.planId, snapshot.version.id, weekStartEpochDay, dayEpochDay, meal.id, meal.type, meal.timeMinutes, targetKcal, response.alternatives, validated.provider.name, validated.model)
    }

    /** Finds compatible persisted meals for the active profile; this path never contacts an AI provider. */
    suspend fun historicalAlternatives(
        weekStartEpochDay: Long,
        dayEpochDay: Long,
        mealId: Long,
    ): List<HistoricalAlternative> {
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId) ?: throw AlternativeException.NeedsInput(listOf("profilo"))
        val snapshot = plans.loadLatestSnapshot(profileId, weekStartEpochDay)
            ?: throw AlternativeException.NeedsInput(listOf("piano alimentare"))
        val day = snapshot.version.days.firstOrNull { it.dateEpochDay == dayEpochDay }
            ?: throw AlternativeException.NeedsInput(listOf("giorno del piano"))
        val sourceMeal = day.meals.firstOrNull { it.id == mealId } ?: throw AlternativeException.NeedsInput(listOf("pasto"))
        val targetKcal = sourceMeal.kcal?.takeIf { it > 0 } ?: return emptyList()
        ensureNotPast(dayEpochDay, sourceMeal.timeMinutes)
        val dietaryProfile = readableDietaryProfile(profile)
        val sourceKey = recipeKey(sourceMeal)
        val seen = mutableSetOf(sourceKey)

        return plans.historicalMeals(profileId, sourceMeal.id)
            .asSequence()
            .filter { candidate ->
                val kcal = candidate.meal.kcal ?: return@filter false
                candidate.planVersionId != snapshot.version.id &&
                    candidate.dateEpochDay <= dayEpochDay &&
                    (candidate.planId != snapshot.planId || candidate.versionNumber < snapshot.version.versionNumber) &&
                    kcal > 0 && abs(kcal - targetKcal) <= targetKcal * LOCAL_CALORIE_TOLERANCE &&
                    slot(candidate.meal) == slot(sourceMeal) &&
                    timeCompatible(sourceMeal, candidate.meal) &&
                    candidate.meal.ingredients.isNotEmpty() &&
                    FoodConstraintValidator.validateIngredientNames(candidate.meal.ingredients.map { it.name }, dietaryProfile, titles = listOf(candidate.meal.title)).isEmpty()
            }
            .filter { it.meal.id != sourceMeal.id && seen.add(recipeKey(it.meal)) }
            .map { candidate ->
                HistoricalAlternative(
                    sourcePlanId = candidate.planId,
                    sourceWeekStartEpochDay = candidate.weekStartEpochDay,
                    sourceVersionId = candidate.planVersionId,
                    sourceVersionNumber = candidate.versionNumber,
                    sourceDateEpochDay = candidate.dateEpochDay,
                    targetPlanVersionId = snapshot.version.id,
                    targetMealId = sourceMeal.id,
                    targetKcal = targetKcal,
                    targetType = sourceMeal.type,
                    targetTimeMinutes = sourceMeal.timeMinutes,
                    meal = candidate.meal,
                    calorieDelta = abs((candidate.meal.kcal ?: targetKcal) - targetKcal),
                )
            }
            .sortedWith(compareBy<HistoricalAlternative> { it.calorieDelta }
                .thenByDescending { preferenceScore(it.meal, dietaryProfile) }
                .thenByDescending { it.sourceVersionId })
            .take(MAX_HISTORICAL_ALTERNATIVES)
            .toList()
    }

    suspend fun applyHistorical(
        weekStartEpochDay: Long,
        dayEpochDay: Long,
        mealId: Long,
        alternative: HistoricalAlternative,
    ): ApplyResult {
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId) ?: throw AlternativeException.NeedsInput(listOf("profilo"))
        val latest = plans.loadLatestSnapshot(profileId, weekStartEpochDay) ?: throw AlternativeException.StalePlan()
        if (latest.version.id != alternative.targetPlanVersionId) throw AlternativeException.StalePlan()
        val sourceDay = latest.version.days.firstOrNull { it.dateEpochDay == dayEpochDay } ?: throw AlternativeException.StalePlan()
        val sourceMeal = sourceDay.meals.firstOrNull { it.id == mealId } ?: throw AlternativeException.StalePlan()
        val targetKcal = sourceMeal.kcal?.takeIf { it > 0 } ?: throw AlternativeException.InvalidAlternative()
        if (targetKcal != alternative.targetKcal || sourceMeal.type != alternative.targetType || sourceMeal.timeMinutes != alternative.targetTimeMinutes) {
            throw AlternativeException.StalePlan()
        }
        val historyMeal = alternative.meal
        val candidateKcal = historyMeal.kcal ?: throw AlternativeException.InvalidAlternative()
        val dietaryProfile = readableDietaryProfile(profile)
        if (alternative.sourcePlanId <= 0L || alternative.sourceVersionId <= 0L || alternative.sourceWeekStartEpochDay == Long.MIN_VALUE ||
            abs(candidateKcal - targetKcal) > targetKcal * LOCAL_CALORIE_TOLERANCE ||
            slot(historyMeal) != slot(sourceMeal) ||
            !timeCompatible(sourceMeal, historyMeal) ||
            FoodConstraintValidator.validateIngredientNames(historyMeal.ingredients.map { it.name }, dietaryProfile, titles = listOf(historyMeal.title)).isNotEmpty()
        ) throw AlternativeException.InvalidAlternative()
        val persistedAlternative = plans.historicalMeals(profileId, sourceMeal.id).firstOrNull {
            it.planId == alternative.sourcePlanId &&
                it.planVersionId == alternative.sourceVersionId &&
                it.dateEpochDay == alternative.sourceDateEpochDay &&
                it.meal.id == historyMeal.id
        }?.meal ?: throw AlternativeException.InvalidAlternative()
        if (persistedAlternative != historyMeal) throw AlternativeException.InvalidAlternative()
        ensureNotPast(sourceDay.dateEpochDay, sourceMeal.timeMinutes)

        val replacement = MealAlternativeCatalogEngine.historicalReplacement(historyMeal, sourceMeal)
        // COMPUTED: totali = somma dello snapshot degli ingredienti. DECLARED: totali dichiarati, kcal non riallineate al target.
        val days = latest.version.days.map { day ->
            val meals = day.meals.map { meal -> if (meal.id == sourceMeal.id) replacement else meal.toDraft() }
            val supplements = day.supplements.map { it.toDraft() }
            DayDraft(
                dateEpochDay = day.dateEpochDay,
                totalKcal = meals.mapNotNull { it.kcal }.sum() + supplements.sumOf { it.kcal },
                proteinG = (sumOrZero(meals.map { it.proteinG }) + supplements.sumOf { it.proteinG.toDouble() }).toFloat(),
                carbsG = (sumOrZero(meals.map { it.carbsG }) + supplements.sumOf { it.carbsG.toDouble() }).toFloat(),
                fatG = (sumOrZero(meals.map { it.fatG }) + supplements.sumOf { it.fatG.toDouble() }).toFloat(),
                targetKcal = day.targetKcal,
                targetProteinG = day.targetProteinG,
                targetCarbsG = day.targetCarbsG,
                targetFatG = day.targetFatG,
                baseTargetKcal = day.baseTargetKcal,
                meals = meals,
                supplements = supplements,
                hydrationNote = day.hydrationNote,
            )
        }
        val versionId = appendSafeVersion(
            profileId = profileId,
            profileUsedForConstraints = profile,
            snapshot = latest,
            targetMealDateEpochDay = sourceDay.dateEpochDay,
            targetMealTimeMinutes = sourceMeal.timeMinutes,
            createdAtEpochMillis = time.nowEpochMillis(),
            draft = PlanVersionDraft(
                source = "LOCAL_HISTORY_SWAP",
                reason = "HISTORY_MEAL_SWAP:${sourceMeal.id}:${alternative.sourceVersionId}:${historyMeal.id}",
                targetKcal = latest.version.targetKcal,
                targetProteinG = latest.version.targetProteinG,
                targetCarbsG = latest.version.targetCarbsG,
                targetFatG = latest.version.targetFatG,
                days = days,
                catalogVersion = catalogVersionAfterSwap(latest.version.catalogVersion, replacement),
            ),
        )
        return ApplyResult(versionId, historyMeal.title, replacement.kcal ?: candidateKcal)
    }

    suspend fun apply(generated: Alternatives, alternative: MealAlternativeContract.Alternative): ApplyResult {
        val computed = alternative.catalogVersion != null
        // MA1: kcal identiche al target. MA2 (COMPUTED): le kcal calcolate sono verificate in [MealAlternativeCatalogEngine.rebuild].
        if (alternative !in generated.items || (!computed && alternative.kcal != generated.targetKcal)) throw AlternativeException.InvalidAlternative()
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId) ?: throw AlternativeException.NeedsInput(listOf("profilo"))
        val dietaryProfile = readableDietaryProfile(profile)
        if (FoodConstraintValidator.validateAlternative(alternative, dietaryProfile).isFailure) {
            throw AlternativeException.InvalidAlternative()
        }
        val latest = plans.loadLatestSnapshot(profileId, generated.weekStartEpochDay) ?: throw AlternativeException.StalePlan()
        if (latest.version.id != generated.sourceVersionId || latest.planId != generated.planId) throw AlternativeException.StalePlan()
        val sourceDay = latest.version.days.firstOrNull { it.dateEpochDay == generated.dayEpochDay } ?: throw AlternativeException.StalePlan()
        val sourceMeal = sourceDay.meals.firstOrNull { it.id == generated.mealId } ?: throw AlternativeException.StalePlan()
        if (sourceMeal.kcal != generated.targetKcal || sourceMeal.type != generated.mealType || sourceMeal.timeMinutes != generated.mealTimeMinutes) throw AlternativeException.StalePlan()
        ensureNotPast(sourceDay.dateEpochDay, sourceMeal.timeMinutes)

        val replacement = if (computed) {
            // Il numero mostrato non e' mai la fonte: si ricostruisce lo snapshot da (foodId, grammi) e si riverifica tutto.
            val catalog = foodCatalogLoader?.load() ?: throw AlternativeException.InvalidAlternative()
            val rebuilt = runCatching {
                MealAlternativeCatalogEngine(catalog)
                    .rebuild(alternative, MealAlternativeCatalogEngine.Input(generated.weekStartEpochDay, generated.dayEpochDay, sourceMeal, dietaryProfile))
            }.getOrElse { throw AlternativeException.InvalidAlternative() }
            rebuilt.toDraft().copy(type = sourceMeal.type, timeMinutes = sourceMeal.timeMinutes)
        } else MealDraft(
            type = sourceMeal.type,
            title = alternative.title,
            timeMinutes = sourceMeal.timeMinutes,
            kcal = generated.targetKcal,
            proteinG = alternative.proteinG,
            carbsG = alternative.carbsG,
            fatG = alternative.fatG,
            preparation = alternative.preparation,
            ingredients = alternative.ingredients.map { ingredient ->
                IngredientDraft(ingredient.name, ingredient.quantity, ingredient.unit, ingredient.displayDose, ingredient.weightState, ingredient.nutritionConfidence, ingredient.category)
            },
        )

        val days = latest.version.days.map { day ->
            val meals = day.meals.map { meal -> if (meal.id == sourceMeal.id) replacement else meal.toDraft() }
            val supplements = day.supplements.map { it.toDraft() }
            DayDraft(
                dateEpochDay = day.dateEpochDay,
                totalKcal = meals.mapNotNull { it.kcal }.sum() + supplements.sumOf { it.kcal },
                proteinG = (sumOrZero(meals.map { it.proteinG }) + supplements.sumOf { it.proteinG.toDouble() }).toFloat(),
                carbsG = (sumOrZero(meals.map { it.carbsG }) + supplements.sumOf { it.carbsG.toDouble() }).toFloat(),
                fatG = (sumOrZero(meals.map { it.fatG }) + supplements.sumOf { it.fatG.toDouble() }).toFloat(),
                targetKcal = day.targetKcal,
                targetProteinG = day.targetProteinG,
                targetCarbsG = day.targetCarbsG,
                targetFatG = day.targetFatG,
                baseTargetKcal = day.baseTargetKcal,
                meals = meals,
                supplements = supplements,
                hydrationNote = day.hydrationNote,
            )
        }

        val versionId = appendSafeVersion(
            profileId = profileId,
            profileUsedForConstraints = profile,
            snapshot = latest,
            targetMealDateEpochDay = sourceDay.dateEpochDay,
            targetMealTimeMinutes = sourceMeal.timeMinutes,
            createdAtEpochMillis = time.nowEpochMillis(),
            draft = PlanVersionDraft(generated.provider, "AI_MEAL_SWAP:${sourceMeal.id}", latest.version.targetKcal, latest.version.targetProteinG, latest.version.targetCarbsG, latest.version.targetFatG, days,
                catalogVersion = catalogVersionAfterSwap(latest.version.catalogVersion, replacement)),
        )
        return ApplyResult(versionId, alternative.title, replacement.kcal ?: generated.targetKcal)
    }

    /** The only plan-write boundary for AI and historical meal replacements. */
    private suspend fun appendSafeVersion(
        profileId: Long,
        profileUsedForConstraints: UserProfileEntity,
        snapshot: com.myfitai.app.domain.food.FoodPlanSnapshot,
        targetMealDateEpochDay: Long,
        targetMealTimeMinutes: Int?,
        createdAtEpochMillis: Long,
        draft: PlanVersionDraft,
    ): Long {
        val baseline = loadSafetyBaseline(profileId)
        if (baseline.profile != profileUsedForConstraints) throw AlternativeException.SafetyChanged()
        if (snapshot.profileId != profileId || snapshot.version.inputsFingerprint?.let { it != baseline.inputsFingerprint } == true) {
            throw AlternativeException.StalePlan()
        }
        val declaration = (HealthDeclarationCodec.decode(baseline.profile.healthDeclarationJson) as? HealthDeclarationDecodeResult.Valid)
            ?.declaration
        if (declaration != null && declaration.confirmedAtEpochMillis > snapshot.version.createdAtEpochMillis) {
            throw AlternativeException.StalePlan()
        }
        validatePlanTargetsAndSafety(snapshot, draft.days, baseline)

        return try {
            plans.createOrAppendVersion(
                profileId = profileId,
                weekStartEpochDay = snapshot.weekStartEpochDay,
                createdAtEpochMillis = createdAtEpochMillis,
                draft = draft,
                expectedProfileSnapshot = baseline.profile,
                expectedPlanBaseline = MealPlanRepository.PlanVersionBaseline(snapshot.planId, snapshot.version.id),
                revalidateBeforeWrite = {
                    val current = loadSafetyBaseline(profileId)
                    if (
                        current.profile != baseline.profile ||
                        current.input != baseline.input ||
                        current.inputsFingerprint != baseline.inputsFingerprint ||
                        current.latestBiaTimestamp != baseline.latestBiaTimestamp ||
                        current.decision != baseline.decision
                    ) {
                        throw MealPlanRepository.SafetySnapshotChanged()
                    }
                },
                // Runs inside Room after profile/safety/CAS checks and directly before the first write.
                validateBeforeAppend = { ensureNotPast(targetMealDateEpochDay, targetMealTimeMinutes) },
            ).second
        } catch (_: MealPlanRepository.PlanSnapshotChanged) {
            throw AlternativeException.StalePlan()
        } catch (_: MealPlanRepository.SafetySnapshotChanged) {
            throw AlternativeException.SafetyChanged()
        }
    }

    /** Reuses the same local gate and calculation inputs as weekly generation; declaration data stays in memory. */
    private suspend fun loadSafetyBaseline(profileId: Long): SafetyBaseline {
        val profile = profiles.get(profileId) ?: throw AlternativeException.SafetyChanged()
        val calculationSnapshot = calculations.profileSnapshot(profileId, time.today())
            ?.takeIf { it.profileId == profileId }
            ?: throw AlternativeException.SafetyChanged()
        if (profiles.get(profileId) != profile) throw AlternativeException.SafetyChanged()

        val declaration = (HealthDeclarationCodec.decode(profile.healthDeclarationJson) as? HealthDeclarationDecodeResult.Valid)
            ?.declaration
            ?.let { HealthDeclarationInput(it.pregnancyOrLactation, it.clinicianManagedConditionOrMedication) }
        val calculation = calculationSnapshot.calculation
        val goal = ProfileCalculationMapper.goal(profile.goal)
        val positiveDeclaration = declaration?.let {
            it.pregnancyOrLactation == true || it.clinicianManagedConditionOrMedication == true
        } == true
        val reductionGoal = SafetyCalorieFloor.appliesTo(goal)
        val safetyRelevant = positiveDeclaration || reductionGoal
        val input = SafetyInput(
            ageYears = ProfileAgeRule.ageYears(profile.birthDateEpochDay, time.today()),
            bmi = calculation.bmi,
            goal = goal,
            biologicalSex = ProfileCalculationMapper.sex(profile.biologicalSex),
            healthDeclaration = declaration,
            bmrKcal = if (safetyRelevant) calculation.bmrKcal ?: Double.NaN else calculation.bmrKcal,
            tdeeKcal = if (safetyRelevant) calculation.tdeeKcal ?: Double.NaN else calculation.tdeeKcal,
            targetKcal = calculation.targetKcal ?: Double.NaN,
        )
        val decision = GenerationSafetyGate.evaluate(input)
        if (decision.outcome.priority >= SafetyOutcome.BLOCK_DEFICIT.priority) {
            throw AlternativeException.SafetyBlocked(decision)
        }
        val maintenanceOnly = positiveDeclaration && goal == LocalCalculationEngine.Goal.MAINTENANCE
        return SafetyBaseline(
            profile = profile,
            input = input,
            decision = decision,
            inputsFingerprint = calculationSnapshot.inputsFingerprint,
            latestBiaTimestamp = calculationSnapshot.latestBiaTimestamp,
            tdeeKcal = calculation.tdeeKcal,
            minimumDailyKcal = if (reductionGoal) {
                calculation.minimumKcal ?: SafetyCalorieFloor.minimumKcal(
                    calculation.bmrKcal ?: Double.NaN,
                    ProfileCalculationMapper.sex(profile.biologicalSex),
                )
            } else null,
            maximumDailyDeficitKcal = SafetyThresholds.MAX_DAILY_DEFICIT_KCAL.takeIf { reductionGoal },
            maintenanceOnly = maintenanceOnly,
        )
    }

    /** Validate every resulting day against its persisted target and the hard S3 floor/cap/maintenance bounds. */
    private fun validatePlanTargetsAndSafety(
        snapshot: com.myfitai.app.domain.food.FoodPlanSnapshot,
        days: List<DayDraft>,
        baseline: SafetyBaseline,
    ) {
        val fallbackKcal = if (baseline.maintenanceOnly) baseline.tdeeKcal else {
            snapshot.version.targetKcal?.toDouble() ?: baseline.input.targetKcal
        } ?: throw AlternativeException.InvalidAlternative()
        val fallbackProtein = snapshot.version.targetProteinG?.toDouble() ?: 1.0
        val fallbackCarbs = snapshot.version.targetCarbsG?.toDouble() ?: 1.0
        val fallbackFat = snapshot.version.targetFatG?.toDouble() ?: 1.0
        if (!fallbackKcal.isFinite() || fallbackKcal <= 0.0) throw AlternativeException.InvalidAlternative()
        val targetsByDay = days.associate { day ->
            val kcal = if (baseline.maintenanceOnly) baseline.tdeeKcal else day.targetKcal?.toDouble() ?: fallbackKcal
            if (kcal == null || !kcal.isFinite() || kcal <= 0.0) throw AlternativeException.InvalidAlternative()
            day.dateEpochDay to NutritionBusinessValidator.Targets(
                kcal = kcal,
                proteinG = day.targetProteinG?.toDouble() ?: fallbackProtein,
                carbsG = day.targetCarbsG?.toDouble() ?: fallbackCarbs,
                fatG = day.targetFatG?.toDouble() ?: fallbackFat,
            )
        }
        val response = NutritionPlanContract.Response(
            weekStartEpochDay = snapshot.weekStartEpochDay,
            days = days.map { day ->
                NutritionPlanContract.GeneratedDay(
                    dateEpochDay = day.dateEpochDay,
                    totalKcal = day.totalKcal ?: throw AlternativeException.InvalidAlternative(),
                    proteinG = day.proteinG ?: throw AlternativeException.InvalidAlternative(),
                    carbsG = day.carbsG ?: throw AlternativeException.InvalidAlternative(),
                    fatG = day.fatG ?: throw AlternativeException.InvalidAlternative(),
                    meals = day.meals.map { meal ->
                        NutritionPlanContract.GeneratedMeal(
                            type = meal.type,
                            title = meal.title,
                            timeMinutes = meal.timeMinutes ?: throw AlternativeException.InvalidAlternative(),
                            kcal = meal.kcal ?: throw AlternativeException.InvalidAlternative(),
                            proteinG = meal.proteinG ?: throw AlternativeException.InvalidAlternative(),
                            carbsG = meal.carbsG ?: throw AlternativeException.InvalidAlternative(),
                            fatG = meal.fatG ?: throw AlternativeException.InvalidAlternative(),
                            preparation = meal.preparation ?: throw AlternativeException.InvalidAlternative(),
                            ingredients = meal.ingredients.map { ingredient ->
                                NutritionPlanContract.GeneratedIngredient(
                                    name = ingredient.name,
                                    quantity = ingredient.quantity,
                                    unit = ingredient.unit,
                                    displayDose = ingredient.displayDose.orEmpty(),
                                    weightState = ingredient.weightState.orEmpty(),
                                    nutritionConfidence = ingredient.nutritionConfidence.orEmpty(),
                                    category = ingredient.category.orEmpty(),
                                    foodId = ingredient.foodId,
                                    grams = ingredient.grams,
                                    kcal = ingredient.kcal,
                                    proteinG = ingredient.proteinG,
                                    carbsG = ingredient.carbsG,
                                    fatG = ingredient.fatG,
                                    fiberG = ingredient.fiberG,
                                    sodiumMg = ingredient.sodiumMg,
                                )
                            },
                            nutritionSource = meal.nutritionSource,
                        )
                    },
                    supplements = day.supplements.map { supplement ->
                        NutritionPlanContract.GeneratedSupplement(
                            kind = supplement.kind,
                            name = supplement.name,
                            dose = supplement.dose,
                            unit = supplement.unit,
                            timeMinutes = supplement.timeMinutes ?: throw AlternativeException.InvalidAlternative(),
                            kcal = supplement.kcal,
                            proteinG = supplement.proteinG,
                            carbsG = supplement.carbsG,
                            fatG = supplement.fatG,
                            notes = supplement.notes.orEmpty(),
                        )
                    },
                    hydrationNote = day.hydrationNote.orEmpty(),
                )
            },
            agentValidation = NutritionPlanContract.AgentValidation(valid = true, notes = "local"),
        )
        val fallbackTargets = targetsByDay.values.firstOrNull() ?: throw AlternativeException.InvalidAlternative()
        val weekStart = LocalDate.ofEpochDay(snapshot.weekStartEpochDay)
        val firstDate = LocalDate.ofEpochDay(days.minOfOrNull { it.dateEpochDay } ?: throw AlternativeException.InvalidAlternative())
        val lastDate = LocalDate.ofEpochDay(days.maxOfOrNull { it.dateEpochDay } ?: throw AlternativeException.InvalidAlternative())
        val periodWeeks = (ChronoUnit.DAYS.between(NutritionPlanPeriod.monday(weekStart), lastDate) / 7 + 1).toInt()
        val dietaryProfile = DietaryProfile.parse(baseline.profile.dietaryPreferencesJson)
        if (NutritionPlanContract.validateBusiness(
                response = response,
                expectedWeekStart = weekStart,
                targets = fallbackTargets,
                mealsPerDay = days.firstOrNull()?.meals?.size ?: 0,
                dailyTargets = targetsByDay,
                dietaryProfile = dietaryProfile,
                tolerance = NutritionPlanAssessment.REFERENCE_TOLERANCE,
                expectedFirstDate = firstDate,
                expectedPeriodWeeks = periodWeeks,
                maintenanceCeilingKcal = baseline.tdeeKcal.takeIf { SafetyCalorieFloor.appliesTo(baseline.input.goal) },
            ).isFailure
        ) {
            throw AlternativeException.InvalidAlternative()
        }
        NutritionPlanContract.validateSafetyBounds(
            response = response,
            minimumDailyKcal = baseline.minimumDailyKcal,
            tdeeKcal = baseline.tdeeKcal,
            maximumDailyDeficitKcal = baseline.maximumDailyDeficitKcal,
            maintenanceOnly = baseline.maintenanceOnly,
        ).getOrElse { throw AlternativeException.InvalidAlternative() }
    }

    /** Lo scambio eredita la versione del catalogo del piano; se il piano era dichiarato e il nuovo pasto e' COMPUTED, quella del catalogo in uso. */
    private fun catalogVersionAfterSwap(inherited: String?, replacement: MealDraft): String? =
        inherited ?: if (replacement.nutritionSource == FoodMeal.NUTRITION_SOURCE_COMPUTED) foodCatalogLoader?.load()?.version?.toString() else null

    private fun buildPrompt(dietaryProfile: DietaryProfile, day: FoodPlanDay, meal: FoodMeal, targetKcal: Int): String = buildString {
        appendLine("MEAL_TYPE:${meal.type}")
        appendLine("MEAL_TIME_MINUTES:${meal.timeMinutes ?: "unknown"}")
        appendLine("DAY:${LocalDate.ofEpochDay(day.dateEpochDay)}")
        appendLine("CURRENT:${meal.title}|${meal.kcal}kcal|P${meal.proteinG}|C${meal.carbsG}|F${meal.fatG}")
        appendLine("TARGET_KCAL_EXACT:$targetKcal")
        appendLine("DP:${dietaryProfile.toPromptCompact()}")
        appendLine("OUTPUT_FORMAT: The data string MUST start with MA1 on a line by itself. For each of exactly 5 alternatives output one A record followed immediately by one or more I records. Finish with exactly one V record. Never repeat MA1 as an alternative tag; never use MFP1, D/F/M/H records, JSON inside data, markdown, or prose.")
        appendLine("A record has exactly 8 pipe-separated fields including its tag: A|title|kcal|proteinG|carbsG|fatG|preparation|reason")
        appendLine("I record has exactly 8 fields including its tag: I|name|quantity|unit|displayDose|weightState|nutritionConfidence|category")
        appendLine("V record has exactly 3 fields including its tag and must be last: V|1_or_0|notes")
        appendLine("FORMAT_EXAMPLE_ONLY: copy the record structure shown in SYSTEM_PROMPT; do not copy example foods or numbers.")
        appendLine("A/I/E/S in DP are hard constraints. D/P are soft preferences. Every alternative kcal MUST equal TARGET_KCAL_EXACT exactly. Keep macros close and include caloric condiments; list every ingredient separately with numeric dose and practical displayDose.")
    }

    private fun readableDietaryProfile(profile: com.myfitai.app.data.local.entity.UserProfileEntity): DietaryProfile =
        DietaryProfile.parse(profile.dietaryPreferencesJson).also {
            if (it.isUnreadable) throw AlternativeException.ConstraintsUnreadable()
        }

    private fun ensureNotPast(dayEpochDay: Long, mealTimeMinutes: Int?) {
        val date = LocalDate.ofEpochDay(dayEpochDay)
        val now = java.time.Instant.ofEpochMilli(time.nowEpochMillis()).atZone(time.zoneId)
        val today = now.toLocalDate()
        if (date.isBefore(today)) throw AlternativeException.PastMeal()
        if (date == today && mealTimeMinutes != null) {
            val nowMinutes = now.hour * 60 + now.minute
            if (mealTimeMinutes <= nowMinutes) throw AlternativeException.PastMeal()
        }
    }

    private fun recipeKey(meal: FoodMeal): String = buildString {
        append(DietaryProfile.normalize(meal.title)).append('|')
        append(meal.ingredients.map { DietaryProfile.normalize(it.name) }.filter { it.isNotBlank() }.sorted().joinToString("|"))
    }

    private fun slot(meal: FoodMeal): MealSlot = MealSlotClassifier.classify(meal.type, meal.timeMinutes)

    private fun timeCompatible(target: FoodMeal, candidate: FoodMeal): Boolean {
        val targetSlot = slot(target)
        if (targetSlot == MealSlot.OTHER && DietaryProfile.normalize(target.type) != DietaryProfile.normalize(candidate.type)) return false
        if (targetSlot != MealSlot.LUNCH && targetSlot != MealSlot.DINNER) return true
        val targetTime = target.timeMinutes ?: return true
        val candidateTime = candidate.timeMinutes ?: return true
        return abs(targetTime - candidateTime) <= LUNCH_DINNER_TIME_TOLERANCE_MINUTES
    }

    private fun preferenceScore(meal: FoodMeal, profile: DietaryProfile): Int {
        val ingredientNames = meal.ingredients.map { DietaryProfile.normalize(it.name) }
        val preferred = profile.preferredFoods.count { preference ->
            val normalized = DietaryProfile.normalize(preference)
            normalized.isNotBlank() && ingredientNames.any { it.contains(normalized) }
        }
        val disliked = profile.dislikedFoods.count { preference ->
            val normalized = DietaryProfile.normalize(preference)
            normalized.isNotBlank() && ingredientNames.any { it.contains(normalized) }
        }
        return preferred - disliked
    }

    // Le alternative MA1 arrivano dal contratto senza snapshot e restano "dichiarate"; quelle MA2 si ricostruiscono da (foodId, grammi).
    private fun FoodSupplement.toDraft() = SupplementDraft(kind, name, dose, unit, timeMinutes, kcal, proteinG, carbsG, fatG, notes)
    private fun sumOrZero(values: List<Float?>): Double = values.filterNotNull().sumOf { it.toDouble() }

    companion object {
        private const val LOCAL_CALORIE_TOLERANCE = 0.10
        private const val LUNCH_DINNER_TIME_TOLERANCE_MINUTES = 120
        private const val MAX_HISTORICAL_ALTERNATIVES = 20
        private const val SYSTEM_PROMPT = """You are MyFitAI Meal Alternative Agent. Nutrition only. Return only schema JSON. DP uses A=allergies, I=intolerances, E=excluded foods, D=disliked, P=preferred, S=diet style, N=notes. A/I/E/S are hard constraints and must never be violated. D/P are soft preferences. Return exactly 5 distinct alternatives. Every alternative kcal MUST equal TARGET_KCAL_EXACT exactly. Keep meal type/time appropriate, keep macros close, include all caloric ingredients, and never use punitive compensation. The app independently validates hard constraints."""
    }
}
