package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiImageInput
import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.*
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import java.time.Instant
import java.time.ZoneId

class CheatAdjustmentService(
    private val aiRuntime: AiRuntimeService,
    private val plans: MealPlanRepository,
    private val cheats: CheatEntryRepository,
    private val activeProfileStore: ActiveProfileStore,
) {
    data class Input(
        val description: String,
        val quantityText: String?,
        val notes: String?,
        val occurredAtEpochMillis: Long,
        val labelImage: AiImageInput? = null,
    )

    data class Result(
        val cheatId: Long,
        val adapted: Boolean,
        val newVersionId: Long?,
        val estimatedKcal: Int?,
        val estimateSummary: String,
        val adaptationSummary: String,
        val modifiedMeals: List<String>,
    )

    sealed class AdjustmentException(message: String) : Exception(message) {
        class NeedsInput(val fields: List<String>) : AdjustmentException("NEEDS_INPUT: ${fields.joinToString()}")
    }

    suspend fun registerAndAdapt(input: Input): Result {
        val description = input.description.trim()
        if (description.isBlank()) throw AdjustmentException.NeedsInput(listOf("descrizione dello sgarro"))
        require(input.occurredAtEpochMillis > 0L)

        val profileId = activeProfileStore.currentIdOrNull()
            ?: throw AdjustmentException.NeedsInput(listOf("profilo attivo"))
        val zone = ZoneId.systemDefault()
        val occurred = Instant.ofEpochMilli(input.occurredAtEpochMillis).atZone(zone)
        val date = occurred.toLocalDate()
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val snapshot = plans.loadLatestSnapshot(profileId, monday.toEpochDay())

        val rawEntry = CheatEntryEntity(
            profileId = profileId,
            occurredAtEpochMillis = input.occurredAtEpochMillis,
            description = description,
            quantityText = input.quantityText?.trim()?.takeIf { it.isNotBlank() },
            estimatedKcal = null,
            estimatedProteinG = null,
            estimatedCarbsG = null,
            estimatedFatG = null,
            planVersionId = snapshot?.version?.id,
            notes = input.notes?.trim()?.takeIf { it.isNotBlank() },
        )
        val cheatId = cheats.insert(rawEntry)

        if (snapshot == null) {
            return Result(cheatId, false, null, null, "Stima non disponibile", "Sgarro registrato. Non esiste un piano per quella settimana, quindi non è stata applicata alcuna modifica.", emptyList())
        }
        val day = snapshot.version.days.firstOrNull { it.dateEpochDay == date.toEpochDay() }
            ?: return Result(cheatId, false, null, null, "Stima non disponibile", "Sgarro registrato. Nessun giorno del piano corrisponde alla data selezionata.", emptyList())
        val targets = planTargets(snapshot.version)
            ?: return Result(cheatId, false, null, null, "Stima non disponibile", "Sgarro registrato. I target del piano non sono completi, quindi l'app non ha adattato i pasti.", emptyList())

        val minuteOfDay = occurred.hour * 60 + occurred.minute
        val lockedMeals = day.meals.filter { (it.timeMinutes ?: Int.MIN_VALUE) <= minuteOfDay }
        val futureMeals = day.meals.filter { (it.timeMinutes ?: Int.MIN_VALUE) > minuteOfDay }

        val responseAndValidation = runCatching {
            val request = AiStructuredRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildPrompt(input, date.toEpochDay(), minuteOfDay, day, lockedMeals, futureMeals, targets),
                schemaName = CheatAdjustmentContract.SCHEMA_NAME,
                schemaJson = CheatAdjustmentContract.schemaJson,
                maxOutputTokens = 8_000,
                image = input.labelImage,
            )
            var parsed: CheatAdjustmentContract.Response? = null
            val validated = aiRuntime.execute(
                request = request,
                maxSchemaRetries = 1,
                businessValidator = { json -> runCatching {
                    val response = CheatAdjustmentContract.parse(json)
                    CheatAdjustmentContract.validateBusiness(response, lockedMeals, futureMeals, targets).getOrThrow()
                    parsed = response
                } },
            )
            (parsed ?: CheatAdjustmentContract.parse(validated.jsonText)) to validated
        }.getOrElse {
            return Result(
                cheatId = cheatId,
                adapted = false,
                newVersionId = null,
                estimatedKcal = null,
                estimateSummary = "Stima IA non disponibile",
                adaptationSummary = "Lo sgarro è stato comunque salvato. Nessuna modifica al piano è stata applicata perché la stima/validazione IA non è riuscita.",
                modifiedMeals = emptyList(),
            )
        }

        val response = responseAndValidation.first
        val validated = responseAndValidation.second
        cheats.update(rawEntry.copy(
            id = cheatId,
            estimatedKcal = response.estimate.kcal,
            estimatedProteinG = response.estimate.proteinG,
            estimatedCarbsG = response.estimate.carbsG,
            estimatedFatG = response.estimate.fatG,
        ))

        if (!response.adaptationPossible || futureMeals.isEmpty()) {
            return Result(
                cheatId,
                false,
                null,
                response.estimate.kcal,
                estimateText(response.estimate),
                response.adaptationReason.ifBlank { "Sgarro registrato; nessuna compensazione punitiva applicata." },
                emptyList(),
            )
        }

        val replacementByOrder = response.replacementMeals.associateBy { it.sortOrder }
        val adjustedDays = snapshot.version.days.map { sourceDay ->
            if (sourceDay.id != day.id) toDraft(sourceDay) else {
                val adjustedMeals = sourceDay.meals.map { sourceMeal ->
                    replacementByOrder[sourceMeal.sortOrder]?.let { replacement ->
                        MealDraft(
                            type = replacement.type,
                            title = replacement.title,
                            timeMinutes = replacement.timeMinutes,
                            kcal = replacement.kcal,
                            proteinG = replacement.proteinG,
                            carbsG = replacement.carbsG,
                            fatG = replacement.fatG,
                            preparation = replacement.preparation,
                            ingredients = replacement.ingredients.map { IngredientDraft(it.name, it.quantity, it.unit, it.displayDose, it.weightState, it.nutritionConfidence, it.category) },
                        )
                    } ?: toDraft(sourceMeal)
                }
                DayDraft(
                    dateEpochDay = sourceDay.dateEpochDay,
                    totalKcal = adjustedMeals.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum(),
                    proteinG = sumOrNull(adjustedMeals.map { it.proteinG }),
                    carbsG = sumOrNull(adjustedMeals.map { it.carbsG }),
                    fatG = sumOrNull(adjustedMeals.map { it.fatG }),
                    meals = adjustedMeals,
                )
            }
        }

        val versionId = plans.appendVersion(
            planId = snapshot.planId,
            createdAtEpochMillis = System.currentTimeMillis(),
            draft = PlanVersionDraft(
                source = validated.provider.name,
                reason = "CHEAT_ADAPTATION:$cheatId",
                targetKcal = snapshot.version.targetKcal,
                targetProteinG = snapshot.version.targetProteinG,
                targetCarbsG = snapshot.version.targetCarbsG,
                targetFatG = snapshot.version.targetFatG,
                days = adjustedDays,
            ),
        )

        return Result(
            cheatId,
            true,
            versionId,
            response.estimate.kcal,
            estimateText(response.estimate),
            response.adaptationReason.ifBlank { "Sono stati adattati esclusivamente i pasti ancora futuri di oggi." },
            response.replacementMeals.sortedBy { it.sortOrder }.map { "${it.type}: ${it.title}" },
        )
    }

    private fun planTargets(version: FoodPlanVersion): NutritionBusinessValidator.Targets? {
        val kcal = version.targetKcal?.toDouble() ?: return null
        val protein = version.targetProteinG?.toDouble() ?: return null
        val carbs = version.targetCarbsG?.toDouble() ?: return null
        val fat = version.targetFatG?.toDouble() ?: return null
        return NutritionBusinessValidator.Targets(kcal, protein, carbs, fat)
    }

    private fun buildPrompt(input: Input, dateEpochDay: Long, minuteOfDay: Int, day: FoodPlanDay, lockedMeals: List<FoodMeal>, futureMeals: List<FoodMeal>, targets: NutritionBusinessValidator.Targets): String = buildString {
        appendLine("Deviation description: ${input.description.trim()}")
        appendLine("Quantity hint: ${input.quantityText?.trim().orEmpty()}")
        appendLine("User notes: ${input.notes?.trim().orEmpty()}")
        appendLine("Nutrition-label image attached: ${input.labelImage != null}")
        if (input.labelImage != null) {
            appendLine("Use the attached nutrition-label photo as supporting evidence. Read only values that are actually visible. Prefer explicit label values over generic food estimates when the label clearly corresponds to the described product. Distinguish per-100g/per-100ml values from per-serving values. Use the user's quantity hint to scale the consumed amount. If the label is ambiguous or unreadable, lower confidence and fall back cautiously to the textual description instead of inventing numbers.")
        }
        appendLine("Date epoch day: $dateEpochDay; occurred minute of day: $minuteOfDay")
        appendLine("Authoritative daily targets: kcal=${targets.kcal}, proteinG=${targets.proteinG}, carbsG=${targets.carbsG}, fatG=${targets.fatG}")
        appendLine("LOCKED meals at or before the event:")
        lockedMeals.forEach { appendLine(mealLine(it)) }
        appendLine("Only these future meals may be replaced, preserving sortOrder and timeMinutes:")
        if (futureMeals.isEmpty()) appendLine("NONE") else futureMeals.forEach { appendLine(mealLine(it)) }
        appendLine("Original day totals: kcal=${day.totalKcal}, P=${day.proteinG}, C=${day.carbsG}, F=${day.fatG}")
        appendLine("Estimate conservatively. If balancing requires implausibly small meals, set adaptationPossible=false with replacementMeals=[].")
        appendLine("If possible, return exactly one replacement per future meal, never move times, and keep each replacement >=100 kcal. Count oils, sauces, condiments and caloric drinks.")
        appendLine("The app will independently verify locked meals + estimate + replacements within ±3% of the original daily targets. agentValidation is advisory only.")
    }

    private fun mealLine(meal: FoodMeal) = "sortOrder=${meal.sortOrder}; time=${meal.timeMinutes}; type=${meal.type}; title=${meal.title}; kcal=${meal.kcal}; P=${meal.proteinG}; C=${meal.carbsG}; F=${meal.fatG}"

    private fun toDraft(day: FoodPlanDay) = DayDraft(day.dateEpochDay, day.totalKcal, day.proteinG, day.carbsG, day.fatG, day.meals.map(::toDraft))
    private fun toDraft(meal: FoodMeal) = MealDraft(
        meal.type, meal.title, meal.timeMinutes, meal.kcal, meal.proteinG, meal.carbsG, meal.fatG, meal.preparation,
        meal.ingredients.map { IngredientDraft(it.name, it.quantity, it.unit, it.displayDose, it.weightState, it.nutritionConfidence, it.category) },
    )
    private fun sumOrNull(values: List<Float?>): Float? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    private fun estimateText(e: CheatAdjustmentContract.Estimate) = "≈ ${e.kcal} kcal · P ${e.proteinG.toInt()}g · C ${e.carbsG.toInt()}g · F ${e.fatG.toInt()}g (${e.confidence})"

    companion object {
        private const val SYSTEM_PROMPT = """You are MyFitAI Nutrition Deviation Agent. Return only JSON matching the supplied schema. Estimate the food event cautiously. If a nutrition-label image is attached, treat it as supporting evidence and use visible label values when reliable; never invent unreadable values. You may modify only meals explicitly listed as future meals for the same day. Never modify past meals or later days, never move meal times, and never use punitive fasting or extreme restriction. If a valid daily balance within ±3% cannot be achieved with reasonable future meals of at least 100 kcal each, set adaptationPossible=false and return no replacements. The app is authoritative for validation."""
    }
}
