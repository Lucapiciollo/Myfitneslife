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
import java.time.LocalDate
import java.util.Locale

/**
 * Ephemeral AI alternative generator for one planned meal.
 * Suggestions are not persisted. Only an explicitly accepted alternative creates a new immutable plan version.
 */
class MealAlternativeService(
    private val aiRuntime: AiRuntimeService,
    private val profiles: UserProfileRepository,
    private val plans: MealPlanRepository,
    private val activeProfileStore: ActiveProfileStore,
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

    data class ApplyResult(
        val versionId: Long,
        val title: String,
        val kcal: Int,
    )

    sealed class AlternativeException(message: String) : Exception(message) {
        class NeedsInput(val fields: List<String>) : AlternativeException("NEEDS_INPUT: ${fields.joinToString()}")
        class PastMeal : AlternativeException("I pasti già trascorsi restano storico e non possono essere sostituiti.")
        class StalePlan : AlternativeException("Il piano è cambiato: genera di nuovo le alternative.")
        class InvalidAlternative : AlternativeException("L'alternativa non rispetta i vincoli del pasto.")
    }

    suspend fun generate(
        weekStartEpochDay: Long,
        dayEpochDay: Long,
        mealId: Long,
    ): Alternatives {
        val profileId = activeProfileStore.currentIdOrNull()
            ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId)
            ?: throw AlternativeException.NeedsInput(listOf("profilo"))
        val snapshot = plans.loadLatestSnapshot(profileId, weekStartEpochDay)
            ?: throw AlternativeException.NeedsInput(listOf("piano alimentare"))
        val day = snapshot.version.days.firstOrNull { it.dateEpochDay == dayEpochDay }
            ?: throw AlternativeException.NeedsInput(listOf("giorno del piano"))
        val meal = day.meals.firstOrNull { it.id == mealId }
            ?: throw AlternativeException.NeedsInput(listOf("pasto"))
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

        return Alternatives(
            planId = snapshot.planId,
            sourceVersionId = snapshot.version.id,
            weekStartEpochDay = weekStartEpochDay,
            dayEpochDay = dayEpochDay,
            mealId = meal.id,
            mealType = meal.type,
            mealTimeMinutes = meal.timeMinutes,
            targetKcal = targetKcal,
            items = response.alternatives,
            provider = validated.provider.name,
            model = validated.model,
        )
    }

    suspend fun apply(
        generated: Alternatives,
        alternative: MealAlternativeContract.Alternative,
    ): ApplyResult {
        if (alternative !in generated.items || alternative.kcal != generated.targetKcal) {
            throw AlternativeException.InvalidAlternative()
        }
        val profileId = activeProfileStore.currentIdOrNull()
            ?: throw AlternativeException.NeedsInput(listOf("profilo attivo"))
        val latest = plans.loadLatestSnapshot(profileId, generated.weekStartEpochDay)
            ?: throw AlternativeException.StalePlan()
        if (latest.version.id != generated.sourceVersionId || latest.planId != generated.planId) {
            throw AlternativeException.StalePlan()
        }
        val sourceDay = latest.version.days.firstOrNull { it.dateEpochDay == generated.dayEpochDay }
            ?: throw AlternativeException.StalePlan()
        val sourceMeal = sourceDay.meals.firstOrNull { it.id == generated.mealId }
            ?: throw AlternativeException.StalePlan()
        if (sourceMeal.kcal != generated.targetKcal || sourceMeal.type != generated.mealType || sourceMeal.timeMinutes != generated.mealTimeMinutes) {
            throw AlternativeException.StalePlan()
        }
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

        val days = latest.version.days.map { day ->
            val meals = day.meals.map { meal ->
                if (meal.id == sourceMeal.id) replacement else meal.toDraft()
            }
            DayDraft(
                dateEpochDay = day.dateEpochDay,
                totalKcal = meals.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum(),
                proteinG = sumOrNull(meals.map { it.proteinG }),
                carbsG = sumOrNull(meals.map { it.carbsG }),
                fatG = sumOrNull(meals.map { it.fatG }),
                meals = meals,
            )
        }

        val versionId = plans.appendVersion(
            planId = latest.planId,
            createdAtEpochMillis = System.currentTimeMillis(),
            draft = PlanVersionDraft(
                source = generated.provider,
                reason = "AI_MEAL_SWAP:${sourceMeal.id}",
                targetKcal = latest.version.targetKcal,
                targetProteinG = latest.version.targetProteinG,
                targetCarbsG = latest.version.targetCarbsG,
                targetFatG = latest.version.targetFatG,
                days = days,
            ),
        )
        return ApplyResult(versionId = versionId, title = alternative.title, kcal = generated.targetKcal)
    }

    private fun buildPrompt(
        dietaryPreferencesJson: String?,
        day: FoodPlanDay,
        meal: FoodMeal,
        targetKcal: Int,
    ): String = buildString {
        appendLine("MEAL_TYPE:${meal.type}")
        appendLine("MEAL_TIME_MINUTES:${meal.timeMinutes ?: "unknown"}")
        appendLine("DAY:${LocalDate.ofEpochDay(day.dateEpochDay)}")
        appendLine("CURRENT:${meal.title}|${meal.kcal}kcal|P${meal.proteinG}|C${meal.carbsG}|F${meal.fatG}")
        appendLine("TARGET_KCAL_EXACT:$targetKcal")
        appendLine("PREFERENCES:${dietaryPreferencesJson.orEmpty()}")
        appendLine("Return exactly 5 alternatives appropriate for this exact meal type and scheduled time. Every alternative kcal MUST equal TARGET_KCAL_EXACT exactly. Keep macros as close as reasonably possible to the original meal without violating the exact kcal constraint. Use practical quantities and include all caloric ingredients/condiments.")
    }

    private fun ensureNotPast(dayEpochDay: Long, mealTimeMinutes: Int?) {
        val date = LocalDate.ofEpochDay(dayEpochDay)
        val today = LocalDate.now()
        if (date.isBefore(today)) throw AlternativeException.PastMeal()
        if (date == today && mealTimeMinutes != null) {
            val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            if (mealTimeMinutes <= now) throw AlternativeException.PastMeal()
        }
    }

    private fun FoodMeal.toDraft() = MealDraft(
        type = type,
        title = title,
        timeMinutes = timeMinutes,
        kcal = kcal,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        preparation = preparation,
        ingredients = ingredients.map { ingredient ->
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

    private fun sumOrNull(values: List<Float?>): Float? {
        val present = values.filterNotNull()
        return present.takeIf { it.isNotEmpty() }?.sum()
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are MyFitAI Meal Alternative Agent. Return only JSON matching the supplied schema.
Generate alternatives ONLY for the supplied planned meal.
HARD RULES:
- Return exactly 5 distinct alternatives.
- Every alternative kcal MUST be exactly equal to TARGET_KCAL_EXACT. This is a strict invariant, not a preference or tolerance.
- Alternatives must be appropriate for the supplied MEAL_TYPE and MEAL_TIME_MINUTES. A breakfast stays breakfast-like; a snack stays snack-like; lunch/dinner stay appropriate to that meal. Do not propose a large lunch/dinner food at snack time unless the source meal itself is that category.
- Keep the original meal time and meal type conceptually unchanged; the app preserves them locally.
- Respect only dietary preferences supplied by the app. Never invent allergies, intolerances or medical conditions.
- Prefer macros close to the original meal while prioritizing the exact calorie invariant.
- Include all caloric ingredients, oils, sauces, dressings and drinks in ingredient quantities.
- Use realistic, practical portions. No punitive restriction or compensatory behavior.
- Keep reason concise and factual.
agentValidation is advisory only; the app independently enforces the calorie invariant and stale-plan safety.
"""
    }
}
