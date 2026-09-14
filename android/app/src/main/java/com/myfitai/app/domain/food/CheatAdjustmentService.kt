package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiImageInput
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.*
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

class CheatAdjustmentService(
    private val aiRuntime: AiRuntimeGateway,
    private val plans: MealPlanRepository,
    private val cheats: CheatEntryRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
) {
    data class Input(
        val description: String,
        val quantityText: String?,
        val notes: String?,
        val occurredAtEpochMillis: Long,
        val labelImage: AiImageInput? = null,
    )

    /** Ephemeral result: nothing has been persisted yet. */
    data class Understanding(
        val understoodFood: String,
        val estimate: CheatAdjustmentContract.Estimate,
        val provider: String,
        val model: String,
        internal val inputFingerprint: String,
    ) {
        val estimateSummary: String
            get() = "≈ ${estimate.kcal} kcal · P ${estimate.proteinG.toInt()}g · C ${estimate.carbsG.toInt()}g · F ${estimate.fatG.toInt()}g (${estimate.confidence})"
    }

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
        class PreviewStale : AdjustmentException("La descrizione è cambiata: fai rivalutare lo sgarro all'IA prima di confermare.")
    }

    /**
     * First phase. The AI explains what it understood and estimates the event.
     * No CheatEntry and no plan version are written here.
     */
    suspend fun analyze(input: Input): Understanding {
        validateInput(input)
        activeProfileStore.currentIdOrNull() ?: throw AdjustmentException.NeedsInput(listOf("profilo attivo"))

        val request = AiStructuredRequest(
            systemPrompt = UNDERSTANDING_SYSTEM_PROMPT,
            userPrompt = buildUnderstandingPrompt(input),
            schemaName = CheatUnderstandingContract.SCHEMA_NAME,
            schemaJson = CheatUnderstandingContract.schemaJson,
            maxOutputTokens = 2_000,
            image = input.labelImage,
        )
        var parsed: CheatUnderstandingContract.Preview? = null
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 1,
            businessValidator = { json -> runCatching {
                val preview = CheatUnderstandingContract.parse(json)
                CheatUnderstandingContract.validate(preview).getOrThrow()
                parsed = preview
            } },
        )
        val preview = parsed ?: CheatUnderstandingContract.parse(validated.jsonText)
        return Understanding(
            understoodFood = preview.understoodFood,
            estimate = preview.estimate,
            provider = validated.provider.name,
            model = validated.model,
            inputFingerprint = fingerprint(input),
        )
    }

    /**
     * Second phase. Only a previously displayed/confirmed understanding may be persisted.
     * If there is a plan to adapt, the second AI call is constrained to the already-confirmed estimate.
     */
    suspend fun registerAndAdapt(input: Input, confirmed: Understanding): Result {
        validateInput(input)
        if (confirmed.inputFingerprint != fingerprint(input)) throw AdjustmentException.PreviewStale()

        val description = input.description.trim()
        val profileId = activeProfileStore.currentIdOrNull()
            ?: throw AdjustmentException.NeedsInput(listOf("profilo attivo"))
        val zone = time.zoneId
        val occurred = Instant.ofEpochMilli(input.occurredAtEpochMillis).atZone(zone)
        val date = occurred.toLocalDate()
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val snapshot = plans.loadLatestSnapshot(profileId, monday.toEpochDay())

        val rawEntry = CheatEntryEntity(
            profileId = profileId,
            occurredAtEpochMillis = input.occurredAtEpochMillis,
            description = description,
            quantityText = input.quantityText?.trim()?.takeIf { it.isNotBlank() },
            estimatedKcal = confirmed.estimate.kcal,
            estimatedProteinG = confirmed.estimate.proteinG,
            estimatedCarbsG = confirmed.estimate.carbsG,
            estimatedFatG = confirmed.estimate.fatG,
            planVersionId = snapshot?.version?.id,
            notes = input.notes?.trim()?.takeIf { it.isNotBlank() },
        )
        val cheatId = cheats.insert(rawEntry)

        if (snapshot == null) {
            return Result(cheatId, false, null, confirmed.estimate.kcal, confirmed.estimateSummary,
                "Sgarro confermato e registrato. Non esiste un piano per quella settimana, quindi non è stata applicata alcuna modifica.", emptyList())
        }
        val day = snapshot.version.days.firstOrNull { it.dateEpochDay == date.toEpochDay() }
            ?: return Result(cheatId, false, null, confirmed.estimate.kcal, confirmed.estimateSummary,
                "Sgarro confermato e registrato. Nessun giorno del piano corrisponde alla data selezionata.", emptyList())
        val targets = planTargets(snapshot.version)
            ?: return Result(cheatId, false, null, confirmed.estimate.kcal, confirmed.estimateSummary,
                "Sgarro confermato e registrato. I target del piano non sono completi, quindi l'app non ha adattato i pasti.", emptyList())

        val minuteOfDay = occurred.hour * 60 + occurred.minute
        val lockedMeals = day.meals.filter { (it.timeMinutes ?: Int.MIN_VALUE) <= minuteOfDay }
        val futureMeals = day.meals.filter { (it.timeMinutes ?: Int.MIN_VALUE) > minuteOfDay }

        if (futureMeals.isEmpty()) {
            return Result(cheatId, false, null, confirmed.estimate.kcal, confirmed.estimateSummary,
                "Sgarro registrato. Non ci sono altri pasti futuri da adattare oggi.", emptyList())
        }

        val responseAndValidation = runCatching {
            val request = AiStructuredRequest(
                systemPrompt = ADJUSTMENT_SYSTEM_PROMPT,
                userPrompt = buildAdjustmentPrompt(input, confirmed, date.toEpochDay(), minuteOfDay, day, lockedMeals, futureMeals, targets),
                schemaName = CheatAdjustmentContract.SCHEMA_NAME,
                schemaJson = CheatAdjustmentContract.schemaJson,
                maxOutputTokens = 8_000,
                image = null,
            )
            var parsed: CheatAdjustmentContract.Response? = null
            val validated = aiRuntime.execute(
                request = request,
                maxSchemaRetries = 1,
                businessValidator = { json -> runCatching {
                    val response = CheatAdjustmentContract.parse(json)
                    requireSameEstimate(response.estimate, confirmed.estimate)
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
                estimatedKcal = confirmed.estimate.kcal,
                estimateSummary = confirmed.estimateSummary,
                adaptationSummary = "Lo sgarro confermato è stato salvato. Nessuna modifica al piano è stata applicata perché l'adattamento IA non è riuscito o non ha rispettato la stima già confermata.",
                modifiedMeals = emptyList(),
            )
        }

        val response = responseAndValidation.first
        val validated = responseAndValidation.second
        if (!response.adaptationPossible) {
            return Result(
                cheatId,
                false,
                null,
                confirmed.estimate.kcal,
                confirmed.estimateSummary,
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
                    totalKcal = adjustedMeals.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum()?.plus(sourceDay.supplements.sumOf { it.kcal }),
                    proteinG = sumOrNull(adjustedMeals.map { it.proteinG })?.plus(sourceDay.supplements.sumOf { it.proteinG.toDouble() }.toFloat()),
                    carbsG = sumOrNull(adjustedMeals.map { it.carbsG })?.plus(sourceDay.supplements.sumOf { it.carbsG.toDouble() }.toFloat()),
                    fatG = sumOrNull(adjustedMeals.map { it.fatG })?.plus(sourceDay.supplements.sumOf { it.fatG.toDouble() }.toFloat()),
                    meals = adjustedMeals,
                    supplements = sourceDay.supplements.map { it.toDraft() },
                    hydrationNote = sourceDay.hydrationNote,
                )
            }
        }

        val versionId = plans.appendVersion(
            planId = snapshot.planId,
            createdAtEpochMillis = time.nowEpochMillis(),
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
            confirmed.estimate.kcal,
            confirmed.estimateSummary,
            response.adaptationReason.ifBlank { "Sono stati adattati esclusivamente i pasti ancora futuri di oggi." },
            response.replacementMeals.sortedBy { it.sortOrder }.map { "${it.type}: ${it.title}" },
        )
    }

    private fun validateInput(input: Input) {
        if (input.description.trim().isBlank()) throw AdjustmentException.NeedsInput(listOf("descrizione dello sgarro"))
        require(input.occurredAtEpochMillis > 0L)
    }

    private fun fingerprint(input: Input): String = listOf(
        input.description.trim(),
        input.quantityText?.trim().orEmpty(),
        input.notes?.trim().orEmpty(),
        input.occurredAtEpochMillis.toString(),
    ).joinToString("|")

    private fun requireSameEstimate(actual: CheatAdjustmentContract.Estimate, confirmed: CheatAdjustmentContract.Estimate) {
        require(actual.kcal == confirmed.kcal) { "CONFIRMED_KCAL_CHANGED" }
        require(abs(actual.proteinG - confirmed.proteinG) <= 0.05f) { "CONFIRMED_PROTEIN_CHANGED" }
        require(abs(actual.carbsG - confirmed.carbsG) <= 0.05f) { "CONFIRMED_CARBS_CHANGED" }
        require(abs(actual.fatG - confirmed.fatG) <= 0.05f) { "CONFIRMED_FAT_CHANGED" }
    }

    private fun planTargets(version: FoodPlanVersion): NutritionBusinessValidator.Targets? {
        val kcal = version.targetKcal?.toDouble() ?: return null
        val protein = version.targetProteinG?.toDouble() ?: return null
        val carbs = version.targetCarbsG?.toDouble() ?: return null
        val fat = version.targetFatG?.toDouble() ?: return null
        return NutritionBusinessValidator.Targets(kcal, protein, carbs, fat)
    }

    private fun buildUnderstandingPrompt(input: Input): String = buildString {
        appendLine("User description: ${input.description.trim()}")
        appendLine("Quantity hint: ${input.quantityText?.trim().orEmpty()}")
        appendLine("Additional clarification/notes: ${input.notes?.trim().orEmpty()}")
        appendLine("Nutrition-label image attached: ${input.labelImage != null}")
        if (input.labelImage != null) {
            appendLine("Read the attached nutrition label only when values are clearly visible. Distinguish per-100g/per-100ml from per-serving data and scale using the user's consumed quantity. Never invent unreadable values.")
        }
        appendLine("Explain in understoodFood, in concise Italian, exactly what food/product, amount and relevant components you believe were consumed. The user will see this before anything is saved.")
        appendLine("Estimate kcal, protein, carbs and fat for the consumed amount. notes must state assumptions or uncertainty.")
    }

    private fun buildAdjustmentPrompt(
        input: Input,
        confirmed: Understanding,
        dateEpochDay: Long,
        minuteOfDay: Int,
        day: FoodPlanDay,
        lockedMeals: List<FoodMeal>,
        futureMeals: List<FoodMeal>,
        targets: NutritionBusinessValidator.Targets,
    ): String = buildString {
        appendLine("CONFIRMED USER INTERPRETATION: ${confirmed.understoodFood}")
        appendLine("CONFIRMED ESTIMATE - MUST COPY EXACTLY into response.estimate:")
        appendLine("kcal=${confirmed.estimate.kcal}; proteinG=${confirmed.estimate.proteinG}; carbsG=${confirmed.estimate.carbsG}; fatG=${confirmed.estimate.fatG}; confidence=${confirmed.estimate.confidence}; notes=${confirmed.estimate.notes}")
        appendLine("Original user description: ${input.description.trim()}")
        appendLine("Quantity hint: ${input.quantityText?.trim().orEmpty()}")
        appendLine("User notes: ${input.notes?.trim().orEmpty()}")
        appendLine("Date epoch day: $dateEpochDay; occurred minute of day: $minuteOfDay")
        appendLine("Authoritative daily targets: kcal=${targets.kcal}, proteinG=${targets.proteinG}, carbsG=${targets.carbsG}, fatG=${targets.fatG}")
        appendLine("LOCKED meals at or before the event:")
        lockedMeals.forEach { appendLine(mealLine(it)) }
        appendLine("Only these future meals may be replaced, preserving sortOrder and timeMinutes:")
        futureMeals.forEach { appendLine(mealLine(it)) }
        appendLine("Original day totals: kcal=${day.totalKcal}, P=${day.proteinG}, C=${day.carbsG}, F=${day.fatG}")
        appendLine("Do not re-estimate the deviation. Use the confirmed estimate exactly. If balancing requires implausibly small meals, set adaptationPossible=false with replacementMeals=[].")
        appendLine("If possible, return exactly one replacement per future meal, never move times, and keep each replacement >=100 kcal. Count oils, sauces, condiments and caloric drinks.")
        appendLine("The app will independently verify locked meals + confirmed estimate + replacements within ±3% of the original daily targets. agentValidation is advisory only.")
    }

    private fun mealLine(meal: FoodMeal) = "sortOrder=${meal.sortOrder}; time=${meal.timeMinutes}; type=${meal.type}; title=${meal.title}; kcal=${meal.kcal}; P=${meal.proteinG}; C=${meal.carbsG}; F=${meal.fatG}"

    private fun toDraft(day: FoodPlanDay) = DayDraft(
        dateEpochDay = day.dateEpochDay,
        totalKcal = day.totalKcal,
        proteinG = day.proteinG,
        carbsG = day.carbsG,
        fatG = day.fatG,
        meals = day.meals.map(::toDraft),
        supplements = day.supplements.map { it.toDraft() },
        hydrationNote = day.hydrationNote,
    )
    private fun toDraft(meal: FoodMeal) = MealDraft(
        meal.type, meal.title, meal.timeMinutes, meal.kcal, meal.proteinG, meal.carbsG, meal.fatG, meal.preparation,
        meal.ingredients.map { IngredientDraft(it.name, it.quantity, it.unit, it.displayDose, it.weightState, it.nutritionConfidence, it.category) },
    )
    private fun FoodSupplement.toDraft() = SupplementDraft(kind, name, dose, unit, timeMinutes, kcal, proteinG, carbsG, fatG, notes)
    private fun sumOrNull(values: List<Float?>): Float? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    companion object {
        private const val UNDERSTANDING_SYSTEM_PROMPT = """You are MyFitAI Nutrition Understanding Agent. Return only JSON matching the supplied schema. Your first job is to tell the user, in concise Italian, exactly what you understood they consumed, then estimate kcal and macros cautiously. If a nutrition-label image is attached, use only clearly visible values and never invent unreadable data. Nothing is saved at this stage; the user must be able to correct your understanding before confirmation."""

        private const val ADJUSTMENT_SYSTEM_PROMPT = """You are MyFitAI Nutrition Deviation Adaptation Agent. Return only JSON matching the supplied schema. The deviation interpretation and nutritional estimate were already shown to and confirmed by the user: copy that estimate exactly and do not reinterpret it. You may modify only meals explicitly listed as future meals for the same day. Never modify past meals or later days, never move meal times, and never use punitive fasting or extreme restriction. If a valid daily balance within ±3% cannot be achieved with reasonable future meals of at least 100 kcal each, set adaptationPossible=false and return no replacements. The app is authoritative for validation."""
    }
}
