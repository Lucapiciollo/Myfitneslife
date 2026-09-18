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
import com.myfitai.app.data.repository.NutritionRecoveryRepository
import com.myfitai.app.data.repository.WorkoutEnergyExpenditureRepository
import com.myfitai.app.data.local.entity.NutritionRecoveryEventEntity
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import java.time.LocalDate

/**
 * Ephemeral AI alternative generator for one planned meal.
 * Suggestions are not persisted. Only an explicitly accepted alternative creates a new immutable plan version.
 */
class MealAlternativeService(
    private val aiRuntime: AiRuntimeGateway,
    private val profiles: UserProfileRepository,
    private val plans: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val recovery: NutritionRecoveryRepository,
    private val time: TimeProvider = SystemTimeProvider,
    private val exerciseEnergy: WorkoutEnergyExpenditureRepository? = null,
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

    data class ApplyResult(val versionId: Long, val title: String, val kcal: Int, val proteinShortfallG: Float = 0f, val recoveryAddedKcal: Int = 0)

    sealed class AlternativeException(message: String) : Exception(message) {
        class NeedsInput(val fields: List<String>) : AlternativeException("NEEDS_INPUT: ${fields.joinToString()}")
        class PastMeal : AlternativeException("I pasti già trascorsi restano storico e non possono essere sostituiti.")
        class StalePlan : AlternativeException("Il piano è cambiato: genera di nuovo le alternative.")
        class InvalidAlternative : AlternativeException("L'alternativa non rispetta i vincoli del pasto.")
    }

    suspend fun generate(weekStartEpochDay: Long, dayEpochDay: Long, mealId: Long): Alternatives {
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId) ?: throw AlternativeException.NeedsInput(listOf("profilo"))
        val snapshot = plans.loadLatestSnapshot(profileId, weekStartEpochDay) ?: throw AlternativeException.NeedsInput(listOf("piano alimentare"))
        val day = snapshot.version.days.firstOrNull { it.dateEpochDay == dayEpochDay } ?: throw AlternativeException.NeedsInput(listOf("giorno del piano"))
        val meal = day.meals.firstOrNull { it.id == mealId } ?: throw AlternativeException.NeedsInput(listOf("pasto"))
        val targetKcal = meal.kcal ?: throw AlternativeException.NeedsInput(listOf("calorie del pasto"))
        ensureNotPast(dayEpochDay, meal.timeMinutes)

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(profile.dietaryPreferencesJson, day, meal, targetKcal),
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
                parsed = response
            } },
        )
        val response = parsed ?: MealAlternativeContract.parse(validated.jsonText).also {
            MealAlternativeContract.validateBusiness(it, meal).getOrThrow()
        }

        return Alternatives(snapshot.planId, snapshot.version.id, weekStartEpochDay, dayEpochDay, meal.id, meal.type, meal.timeMinutes, targetKcal, response.alternatives, validated.provider.name, validated.model)
    }

    suspend fun apply(generated: Alternatives, alternative: MealAlternativeContract.Alternative): ApplyResult {
        if (alternative !in generated.items || alternative.kcal !in 1..generated.targetKcal) throw AlternativeException.InvalidAlternative()
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
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
            kcal = alternative.kcal,
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
        val updatedDay = days.first { it.dateEpochDay == generated.dayEpochDay }
        val targetProtein = latest.version.targetProteinG?.toDouble()
        val actualProtein = updatedDay.proteinG?.toDouble() ?: 0.0
        val proteinShortfall = if (targetProtein != null && actualProtein < targetProtein * (1.0 - NutritionBusinessValidator.DEFAULT_TOLERANCE)) {
            (targetProtein - actualProtein).toFloat().coerceAtLeast(0f)
        } else 0f
        val targetKcal = latest.version.targetKcal?.toDouble()
        val exerciseKcal = exerciseEnergy?.forDay(profileId, generated.dayEpochDay)?.sumOf { it.caloriesKcal.coerceAtLeast(0) } ?: 0
        val dailyExcess = if (targetKcal != null) ((updatedDay.totalKcal ?: 0) - targetKcal - exerciseKcal).toInt().coerceAtLeast(0) else 0
        val recoveryAdded = registerMealSwapRecoveryIfNeeded(profileId, generated.dayEpochDay, dailyExcess)
        return ApplyResult(versionId, alternative.title, alternative.kcal, proteinShortfall, recoveryAdded)
    }

    private fun buildPrompt(dietaryPreferencesJson: String?, day: FoodPlanDay, meal: FoodMeal, targetKcal: Int): String = buildString {
        appendLine("MEAL_TYPE:${meal.type}")
        appendLine("MEAL_TIME_MINUTES:${meal.timeMinutes ?: "unknown"}")
        appendLine("DAY:${LocalDate.ofEpochDay(day.dateEpochDay)}")
        appendLine("CURRENT:${meal.title}|${meal.kcal}kcal|P${meal.proteinG}|C${meal.carbsG}|F${meal.fatG}")
        appendLine("MAX_KCAL:$targetKcal")
        appendLine("PREFERENCES:${dietaryPreferencesJson.orEmpty()}")
        appendLine("Return exactly 5 alternatives for this meal. Every alternative kcal MUST be greater than 0 and less than or equal to MAX_KCAL. Do not modify later meals. Keep the same meal category and time, recalculate protein/carbs/fat from ingredients, and include caloric condiments.")
    }

    private suspend fun registerMealSwapRecoveryIfNeeded(profileId: Long, dayEpochDay: Long, excessKcal: Int): Int {
        if (excessKcal <= 0) return 0
        if (recovery.activeEventForSource(profileId, dayEpochDay, SOURCE_MEAL_SWAP) != null) return 0
        recovery.insertEvent(NutritionRecoveryEventEntity(profileId = profileId, createdAtEpochMillis = time.nowEpochMillis(), eventEpochDay = dayEpochDay, source = SOURCE_MEAL_SWAP, originalExcessKcal = excessKcal, remainingKcal = excessKcal, recoveredKcal = 0, expiresEpochDay = dayEpochDay + NutritionRecoveryTargetEngine.DEFAULT_RECOVERY_WINDOW_DAYS - 1, status = NutritionRecoveryTargetEngine.STATUS_ACTIVE, reason = "Eccedenza giornaliera dopo sostituzione pasto"))
        return excessKcal
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

    private fun FoodMeal.toDraft() = MealDraft(
        type, title, timeMinutes, kcal, proteinG, carbsG, fatG, preparation,
        ingredients.map { IngredientDraft(it.name, it.quantity, it.unit, it.displayDose, it.weightState, it.nutritionConfidence, it.category) },
    )

    private fun FoodSupplement.toDraft() = SupplementDraft(kind, name, dose, unit, timeMinutes, kcal, proteinG, carbsG, fatG, notes)
    private fun sumOrZero(values: List<Float?>): Double = values.filterNotNull().sumOf { it.toDouble() }

    companion object {
        private const val SOURCE_MEAL_SWAP = "MEAL_SWAP"
        private const val SYSTEM_PROMPT = """You are MyFitAI Meal Alternative Agent. Nutrition only. Return only schema JSON. Return exactly 5 distinct alternatives. Each alternative must use the same meal category and time, have calories > 0 and <= MAX_KCAL, and have macros recalculated from its ingredients. Never modify later meals, include all caloric ingredients, respect supplied preferences, and never use punitive compensation."""
    }
}
