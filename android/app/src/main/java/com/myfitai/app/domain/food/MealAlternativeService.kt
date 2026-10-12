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
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import java.time.LocalDate
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

    sealed class AlternativeException(message: String) : Exception(message) {
        class NeedsInput(val fields: List<String>) : AlternativeException("NEEDS_INPUT: ${fields.joinToString()}")
        class PastMeal : AlternativeException("I pasti già trascorsi restano storico e non possono essere sostituiti.")
        class StalePlan : AlternativeException("Il piano è cambiato: genera di nuovo le alternative.")
        class InvalidAlternative : AlternativeException("L'alternativa non rispetta i vincoli del pasto o del profilo.")
    }

    suspend fun generate(weekStartEpochDay: Long, dayEpochDay: Long, mealId: Long): Alternatives {
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        return generate(profileId, weekStartEpochDay, dayEpochDay, mealId)
    }

    suspend fun generate(profileId: Long, weekStartEpochDay: Long, dayEpochDay: Long, mealId: Long): Alternatives {
        val profile = profiles.get(profileId) ?: throw AlternativeException.NeedsInput(listOf("profilo"))
        val dietaryProfile = DietaryProfile.parse(profile.dietaryPreferencesJson)
        val snapshot = plans.loadLatestSnapshot(profileId, weekStartEpochDay) ?: throw AlternativeException.NeedsInput(listOf("piano alimentare"))
        val day = snapshot.version.days.firstOrNull { it.dateEpochDay == dayEpochDay } ?: throw AlternativeException.NeedsInput(listOf("giorno del piano"))
        val meal = day.meals.firstOrNull { it.id == mealId } ?: throw AlternativeException.NeedsInput(listOf("pasto"))
        val targetKcal = meal.kcal ?: throw AlternativeException.NeedsInput(listOf("calorie del pasto"))
        ensureNotPast(dayEpochDay, meal.timeMinutes)

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
        val dietaryProfile = DietaryProfile.parse(profile.dietaryPreferencesJson)
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
                    FoodConstraintValidator.validateIngredientNames(candidate.meal.ingredients.map { it.name }, dietaryProfile).isEmpty()
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
        val dietaryProfile = DietaryProfile.parse(profile.dietaryPreferencesJson)
        if (alternative.sourcePlanId <= 0L || alternative.sourceVersionId <= 0L || alternative.sourceWeekStartEpochDay == Long.MIN_VALUE ||
            abs(candidateKcal - targetKcal) > targetKcal * LOCAL_CALORIE_TOLERANCE ||
            slot(historyMeal) != slot(sourceMeal) ||
            !timeCompatible(sourceMeal, historyMeal) ||
            FoodConstraintValidator.validateIngredientNames(historyMeal.ingredients.map { it.name }, dietaryProfile).isNotEmpty()
        ) throw AlternativeException.InvalidAlternative()
        val persistedAlternative = plans.historicalMeals(profileId, sourceMeal.id).firstOrNull {
            it.planId == alternative.sourcePlanId &&
                it.planVersionId == alternative.sourceVersionId &&
                it.dateEpochDay == alternative.sourceDateEpochDay &&
                it.meal.id == historyMeal.id
        }?.meal ?: throw AlternativeException.InvalidAlternative()
        if (persistedAlternative != historyMeal) throw AlternativeException.InvalidAlternative()
        ensureNotPast(sourceDay.dateEpochDay, sourceMeal.timeMinutes)

        val replacement = MealDraft(
            type = sourceMeal.type,
            title = historyMeal.title,
            timeMinutes = sourceMeal.timeMinutes,
            kcal = candidateKcal,
            proteinG = historyMeal.proteinG,
            carbsG = historyMeal.carbsG,
            fatG = historyMeal.fatG,
            preparation = historyMeal.preparation,
            ingredients = historyMeal.ingredients.map { ingredient ->
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
        val versionId = plans.appendVersion(
            profileId = profileId,
            planId = latest.planId,
            createdAtEpochMillis = time.nowEpochMillis(),
            draft = PlanVersionDraft(
                source = "LOCAL_HISTORY_SWAP",
                reason = "HISTORY_MEAL_SWAP:${sourceMeal.id}:${alternative.sourceVersionId}:${historyMeal.id}",
                targetKcal = latest.version.targetKcal,
                targetProteinG = latest.version.targetProteinG,
                targetCarbsG = latest.version.targetCarbsG,
                targetFatG = latest.version.targetFatG,
                days = days,
            ),
        )
        return ApplyResult(versionId, historyMeal.title, candidateKcal)
    }

    suspend fun apply(generated: Alternatives, alternative: MealAlternativeContract.Alternative): ApplyResult {
        if (alternative !in generated.items || alternative.kcal != generated.targetKcal) throw AlternativeException.InvalidAlternative()
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId) ?: throw AlternativeException.NeedsInput(listOf("profilo"))
        if (FoodConstraintValidator.validateAlternative(alternative, DietaryProfile.parse(profile.dietaryPreferencesJson)).isFailure) {
            throw AlternativeException.InvalidAlternative()
        }
        val latest = plans.loadLatestSnapshot(profileId, generated.weekStartEpochDay) ?: throw AlternativeException.StalePlan()
        if (latest.version.id != generated.sourceVersionId || latest.planId != generated.planId) throw AlternativeException.StalePlan()
        val sourceDay = latest.version.days.firstOrNull { it.dateEpochDay == generated.dayEpochDay } ?: throw AlternativeException.StalePlan()
        val sourceMeal = sourceDay.meals.firstOrNull { it.id == generated.mealId } ?: throw AlternativeException.StalePlan()
        if (sourceMeal.kcal != generated.targetKcal || sourceMeal.type != generated.mealType || sourceMeal.timeMinutes != generated.mealTimeMinutes) throw AlternativeException.StalePlan()
        ensureNotPast(sourceDay.dateEpochDay, sourceMeal.timeMinutes)

        val replacement = MealDraft(
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

        val versionId = plans.appendVersion(
            profileId = profileId,
            planId = latest.planId,
            createdAtEpochMillis = time.nowEpochMillis(),
            draft = PlanVersionDraft(generated.provider, "AI_MEAL_SWAP:${sourceMeal.id}", latest.version.targetKcal, latest.version.targetProteinG, latest.version.targetCarbsG, latest.version.targetFatG, days),
        )
        return ApplyResult(versionId, alternative.title, generated.targetKcal)
    }

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

    private fun ensureNotPast(dayEpochDay: Long, mealTimeMinutes: Int?) {
        val date = LocalDate.ofEpochDay(dayEpochDay)
        val today = time.today()
        if (date.isBefore(today)) throw AlternativeException.PastMeal()
        if (date == today && mealTimeMinutes != null) {
            val now = time.currentTime().let { it.hour * 60 + it.minute }
            if (mealTimeMinutes <= now) throw AlternativeException.PastMeal()
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

    private fun FoodMeal.toDraft() = MealDraft(
        type, title, timeMinutes, kcal, proteinG, carbsG, fatG, preparation,
        ingredients.map { IngredientDraft(it.name, it.quantity, it.unit, it.displayDose, it.weightState, it.nutritionConfidence, it.category) },
    )

    private fun FoodSupplement.toDraft() = SupplementDraft(kind, name, dose, unit, timeMinutes, kcal, proteinG, carbsG, fatG, notes)
    private fun sumOrZero(values: List<Float?>): Double = values.filterNotNull().sumOf { it.toDouble() }

    companion object {
        private const val LOCAL_CALORIE_TOLERANCE = 0.10
        private const val LUNCH_DINNER_TIME_TOLERANCE_MINUTES = 120
        private const val MAX_HISTORICAL_ALTERNATIVES = 20
        private const val SYSTEM_PROMPT = """You are MyFitAI Meal Alternative Agent. Nutrition only. Return only schema JSON. DP uses A=allergies, I=intolerances, E=excluded foods, D=disliked, P=preferred, S=diet style, N=notes. A/I/E/S are hard constraints and must never be violated. D/P are soft preferences. Return exactly 5 distinct alternatives. Every alternative kcal MUST equal TARGET_KCAL_EXACT exactly. Keep meal type/time appropriate, keep macros close, include all caloric ingredients, and never use punitive compensation. The app independently validates hard constraints."""
    }
}
