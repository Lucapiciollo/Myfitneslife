package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.PlanVersionDraft
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
        class NoPlan : AdjustmentException("Nessun piano disponibile per la settimana selezionata")
        class InvalidAiOutput(reason: String) : AdjustmentException(reason)
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
        val snapshot = plans.loadLatestSnapshot(profileId, monday.toEpochDay()) ?: throw AdjustmentException.NoPlan()
        val day = snapshot.version.days.firstOrNull { it.dateEpochDay == date.toEpochDay() }
            ?: throw AdjustmentException.NoPlan()

        val minuteOfDay = occurred.hour * 60 + occurred.minute
        val lockedMeals = day.meals.filter { (it.timeMinutes ?: Int.MIN_VALUE) <= minuteOfDay }
        val futureMeals = day.meals.filter { (it.timeMinutes ?: Int.MIN_VALUE) > minuteOfDay }
        val targets = planTargets(snapshot.version)
            ?: throw AdjustmentException.NeedsInput(listOf("target nutrizionali del piano"))

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(input, date.toEpochDay(), minuteOfDay, day, lockedMeals, futureMeals, targets),
            schemaName = CheatAdjustmentContract.SCHEMA_NAME,
            schemaJson = CheatAdjustmentContract.schemaJson,
            maxOutputTokens = 8_000,
        )

        var parsed: CheatAdjustmentContract.Response? = null
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 1,
            businessValidator = { json ->
                runCatching {
                    val response = CheatAdjustmentContract.parse(json)
                    CheatAdjustmentContract.validateBusiness(response, lockedMeals, futureMeals, targets).getOrThrow()
                    parsed = response
                }
            },
        )
        val response = parsed ?: runCatching { CheatAdjustmentContract.parse(validated.jsonText) }
            .getOrElse { throw AdjustmentException.InvalidAiOutput("INVALID_SCHEMA") }

        val cheatId = cheats.insert(
            CheatEntryEntity(
                profileId = profileId,
                occurredAtEpochMillis = input.occurredAtEpochMillis,
                description = description,
                quantityText = input.quantityText?.trim()?.takeIf { it.isNotBlank() },
                estimatedKcal = response.estimate.kcal,
                estimatedProteinG = response.estimate.proteinG,
                estimatedCarbsG = response.estimate.carbsG,
                estimatedFatG = response.estimate.fatG,
                planVersionId = snapshot.version.id,
                notes = input.notes?.trim()?.takeIf { it.isNotBlank() },
            )
        )

        if (!response.adaptationPossible || futureMeals.isEmpty()) {
            return Result(
                cheatId = cheatId,
                adapted = false,
                newVersionId = null,
                estimatedKcal = response.estimate.kcal,
                estimateSummary = estimateText(response.estimate),
                adaptationSummary = response.adaptationReason.ifBlank { "Sgarro registrato; nessuna modifica punitiva applicata al piano." },
                modifiedMeals = emptyList(),
            )
        }

        val replacementByOrder = response.replacementMeals.associateBy { it.sortOrder }
        val adjustedDays = snapshot.version.days.map { sourceDay ->
            if (sourceDay.id != day.id) {
                toDraft(sourceDay)
            } else {
                val adjustedMeals = sourceDay.meals.map { sourceMeal ->
                    val replacement = replacementByOrder[sourceMeal.sortOrder]
                    if (replacement == null) toDraft(sourceMeal) else MealDraft(
                        type = replacement.type,
                        title = replacement.title,
                        timeMinutes = replacement.timeMinutes,
                        kcal = replacement.kcal,
                        proteinG = replacement.proteinG,
                        carbsG = replacement.carbsG,
                        fatG = replacement.fatG,
                        preparation = replacement.preparation,
                        ingredients = replacement.ingredients.map { ingredient ->
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
            cheatId = cheatId,
            adapted = true,
            newVersionId = versionId,
            estimatedKcal = response.estimate.kcal,
            estimateSummary = estimateText(response.estimate),
            adaptationSummary = response.adaptationReason.ifBlank { "Sono stati adattati solo i pasti ancora futuri di oggi." },
            modifiedMeals = response.replacementMeals.sortedBy { it.sortOrder }.map { "${it.type}: ${it.title}" },
        )
    }

    private fun planTargets(version: FoodPlanVersion): NutritionBusinessValidator.Targets? {
        val kcal = version.targetKcal?.toDouble() ?: return null
        val protein = version.targetProteinG?.toDouble() ?: return null
        val carbs = version.targetCarbsG?.toDouble() ?: return null
        val fat = version.targetFatG?.toDouble() ?: return null
        return NutritionBusinessValidator.Targets(kcal, protein, carbs, fat)
    }

    private fun buildPrompt(
        input: Input,
        dateEpochDay: Long,
        minuteOfDay: Int,
        day: FoodPlanDay,
        lockedMeals: List<FoodMeal>,
        futureMeals: List<FoodMeal>,
        targets: NutritionBusinessValidator.Targets,
    ): String = buildString {
        appendLine("Deviation description: ${input.description.trim()}")
        appendLine("Quantity hint: ${input.quantityText?.trim().orEmpty()}")
        appendLine("User notes: ${input.notes?.trim().orEmpty()}")
        appendLine("Date epoch day: $dateEpochDay; occurred minute of day: $minuteOfDay")
        appendLine("Authoritative daily targets: kcal=${targets.kcal}, proteinG=${targets.proteinG}, carbsG=${targets.carbsG}, fatG=${targets.fatG}")
        appendLine("Meals at or before the deviation are LOCKED and cannot be changed:")
        lockedMeals.forEach { appendLine(mealLine(it)) }
        appendLine("Only these future meals may be replaced, preserving sortOrder and timeMinutes:")
        if (futureMeals.isEmpty()) appendLine("NONE") else futureMeals.forEach { appendLine(mealLine(it)) }
        appendLine("Original day totals: kcal=${day.totalKcal}, P=${day.proteinG}, C=${day.carbsG}, F=${day.fatG}")
        appendLine("Estimate the deviation conservatively from the provided text. If the remaining target cannot be reached without making future meals implausibly small, set adaptationPossible=false and replacementMeals=[].")
        appendLine("If adaptation is possible, return one replacement for every listed future meal. Do not move meal times. Keep each replacement at least 100 kcal. Count oils, sauces, condiments and caloric drinks.")
        appendLine("The app will verify that locked meals + deviation estimate + replacements stay within ±3% of the original daily targets. agentValidation is advisory only.")
    }

    private fun mealLine(meal: FoodMeal): String =
        "sortOrder=${meal.sortOrder}; time=${meal.timeMinutes}; type=${meal.type}; title=${meal.title}; kcal=${meal.kcal}; P=${meal.proteinG}; C=${meal.carbsG}; F=${meal.fatG}"

    private fun toDraft(day: FoodPlanDay) = DayDraft(
        dateEpochDay = day.dateEpochDay,
        totalKcal = day.totalKcal,
        proteinG = day.proteinG,
        carbsG = day.carbsG,
        fatG = day.fatG,
        meals = day.meals.map(::toDraft),
    )

    private fun toDraft(meal: FoodMeal) = MealDraft(
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

    private fun sumOrNull(values: List<Float?>): Float? {
        val present = values.filterNotNull()
        return present.takeIf { it.isNotEmpty() }?.sum()
    }

    private fun estimateText(estimate: CheatAdjustmentContract.Estimate): String =
        "≈ ${estimate.kcal} kcal · P ${estimate.proteinG.toInt()}g · C ${estimate.carbsG.toInt()}g · F ${estimate.fatG.toInt()}g (${estimate.confidence})"

    companion object {
        private const val SYSTEM_PROMPT = """You are MyFitAI Nutrition Deviation Agent. Return only JSON matching the supplied schema. Estimate the reported food event cautiously. You may modify only meals explicitly listed as future meals for the same day. Never modify past meals, never alter later days, never move meal times, never use punitive fasting or extreme restriction. If a valid daily balance within ±3% cannot be achieved with reasonable future meals of at least 100 kcal each, set adaptationPossible=false and return no replacements. The app, not you, is authoritative for validation."""
    }
}
