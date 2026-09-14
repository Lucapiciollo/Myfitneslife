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
        if (alternative !in generated.items || alternative.kcal != generated.targetKcal) throw AlternativeException.InvalidAlternative()
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
                meals = meals,
                supplements = supplements,
                hydrationNote = day.hydrationNote,
            )
        }

        val versionId = plans.appendVersion(
            planId = latest.planId,
            createdAtEpochMillis = time.nowEpochMillis(),
            draft = PlanVersionDraft(generated.provider, "AI_MEAL_SWAP:${sourceMeal.id}", latest.version.targetKcal, latest.version.targetProteinG, latest.version.targetCarbsG, latest.version.targetFatG, days),
        )
        return ApplyResult(versionId, alternative.title, generated.targetKcal)
    }

    private fun buildPrompt(dietaryPreferencesJson: String?, day: FoodPlanDay, meal: FoodMeal, targetKcal: Int): String = buildString {
        appendLine("MEAL_TYPE:${meal.type}")
        appendLine("MEAL_TIME_MINUTES:${meal.timeMinutes ?: "unknown"}")
        appendLine("DAY:${LocalDate.ofEpochDay(day.dateEpochDay)}")
        appendLine("CURRENT:${meal.title}|${meal.kcal}kcal|P${meal.proteinG}|C${meal.carbsG}|F${meal.fatG}")
        appendLine("TARGET_KCAL_EXACT:$targetKcal")
        appendLine("PREFERENCES:${dietaryPreferencesJson.orEmpty()}")
        appendLine("Return exactly 5 alternatives for this meal. Every alternative kcal MUST equal TARGET_KCAL_EXACT exactly. Keep macros close and include caloric condiments.")
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
        private const val SYSTEM_PROMPT = """You are MyFitAI Meal Alternative Agent. Nutrition only. Return only schema JSON. Return exactly 5 distinct alternatives. Every alternative kcal MUST equal TARGET_KCAL_EXACT exactly. Keep meal type/time appropriate, respect supplied preferences, keep macros close, include all caloric ingredients, and never use punitive compensation."""
    }
}
