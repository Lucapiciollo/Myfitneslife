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
import kotlin.math.abs
import kotlin.math.roundToInt

class CheatAdjustmentService(
    private val aiRuntime: AiRuntimeGateway,
    private val plans: MealPlanRepository,
    private val cheats: CheatEntryRepository,
    private val profiles: UserProfileRepository,
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
            inputFingerprint = inputFingerprint(input),
        )
    }

    suspend fun registerAcceptedSuggestion(input: Input, suggestion: NutritionAdviceContract.Suggestion): Result {
        require(suggestion.estimatedKcal > 0)
        require(listOf(suggestion.proteinG, suggestion.carbsG, suggestion.fatG).all { it >= 0f && it.isFinite() })
        val confirmed = Understanding(
            understoodFood = suggestion.title,
            estimate = CheatAdjustmentContract.Estimate(
                kcal = suggestion.estimatedKcal,
                proteinG = suggestion.proteinG,
                carbsG = suggestion.carbsG,
                fatG = suggestion.fatG,
                confidence = "high",
                notes = "Stima già validata dalla proposta nutrizionale accettata.",
            ),
            provider = "NutritionAdvice",
            model = "validated-suggestion",
            inputFingerprint = inputFingerprint(input),
        )
        return registerAndAdapt(input, confirmed)
    }

    suspend fun registerAndAdapt(input: Input, confirmed: Understanding): Result {
        validateInput(input)
        if (confirmed.inputFingerprint != inputFingerprint(input)) throw AdjustmentException.PreviewStale()

        val description = input.description.trim()
        val profileId = activeProfileStore.currentIdOrNull()
            ?: throw AdjustmentException.NeedsInput(listOf("profilo attivo"))
        val profile = profiles.get(profileId)
            ?: throw AdjustmentException.NeedsInput(listOf("profilo"))
        val dietaryProfile = DietaryProfile.parse(profile.dietaryPreferencesJson)
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
        val targets = planTargets(day, snapshot.version)
            ?: return Result(cheatId, false, null, confirmed.estimate.kcal, confirmed.estimateSummary,
                "Sgarro confermato e registrato. I target del giorno non sono completi, quindi l'app non ha adattato i pasti.", emptyList())

        val minuteOfDay = occurred.hour * 60 + occurred.minute
        val eligibleDays = snapshot.version.days.filter { sourceDay ->
            sourceDay.dateEpochDay > date.toEpochDay() ||
                (sourceDay.dateEpochDay == date.toEpochDay() && sourceDay.meals.any { (it.timeMinutes ?: Int.MIN_VALUE) > minuteOfDay })
        }
        if (eligibleDays.isEmpty()) {
            return Result(cheatId, false, null, confirmed.estimate.kcal, confirmed.estimateSummary,
                "Sgarro registrato. Non ci sono target futuri nella settimana su cui distribuire il surplus.", emptyList())
        }

        var remainingSurplus = confirmed.estimate.kcal.toDouble()
        val reductions = mutableMapOf<Long, Int>()
        val adjustedDays = snapshot.version.days.map { sourceDay ->
            val eligibleIndex = eligibleDays.indexOfFirst { it.id == sourceDay.id }
            if (eligibleIndex < 0) return@map toDraft(sourceDay)
            val baseTarget = (sourceDay.targetKcal ?: sourceDay.totalKcal ?: snapshot.version.targetKcal ?: 0).coerceAtLeast(1)
            val maxDailyReduction = (baseTarget * MAX_DAILY_REDUCTION_RATIO).roundToInt().coerceAtLeast(1)
            val reduction = minOf(remainingSurplus.roundToInt(), maxDailyReduction)
            remainingSurplus -= reduction
            reductions[sourceDay.id] = reduction
            val eventDay = sourceDay.dateEpochDay == date.toEpochDay()
            val adjustable = sourceDay.meals.filter { !eventDay || (it.timeMinutes ?: Int.MIN_VALUE) > minuteOfDay }
            val locked = sourceDay.meals - adjustable.toSet()
            val lockedKcal = locked.sumOf { it.kcal ?: 0 } + sourceDay.supplements.sumOf { it.kcal }
            val adjustableKcal = adjustable.sumOf { it.kcal ?: 0 }
            val newTarget = (baseTarget - reduction).coerceAtLeast(1)
            val targetForAdjustableMeals = (newTarget - lockedKcal).coerceAtLeast(0)
            val scale = if (adjustableKcal > 0) targetForAdjustableMeals.toDouble() / adjustableKcal else 1.0
            val adjustedMeals = sourceDay.meals.map { sourceMeal ->
                if (sourceMeal !in adjustable || sourceMeal.kcal == null) toDraft(sourceMeal)
                else scaleMeal(sourceMeal, scale)
            }
            DayDraft(
                dateEpochDay = sourceDay.dateEpochDay,
                totalKcal = adjustedMeals.mapNotNull { it.kcal }.sum() + sourceDay.supplements.sumOf { it.kcal },
                proteinG = sumOrNull(adjustedMeals.map { it.proteinG })?.plus(sourceDay.supplements.sumOf { it.proteinG.toDouble() }.toFloat()),
                carbsG = sumOrNull(adjustedMeals.map { it.carbsG })?.plus(sourceDay.supplements.sumOf { it.carbsG.toDouble() }.toFloat()),
                fatG = sumOrNull(adjustedMeals.map { it.fatG })?.plus(sourceDay.supplements.sumOf { it.fatG.toDouble() }.toFloat()),
                targetKcal = newTarget,
                targetProteinG = sourceDay.targetProteinG,
                targetCarbsG = sourceDay.targetCarbsG,
                targetFatG = sourceDay.targetFatG,
                meals = adjustedMeals,
                supplements = sourceDay.supplements.map { it.toDraft() },
                hydrationNote = sourceDay.hydrationNote,
            )
        }

        val versionId = plans.appendVersion(
            profileId = profileId,
            planId = snapshot.planId,
            createdAtEpochMillis = time.nowEpochMillis(),
            draft = PlanVersionDraft(
                source = "LOCAL_RESERVOIR",
                reason = "CHEAT_WEEKLY_RESERVOIR:$cheatId",
                targetKcal = snapshot.version.targetKcal,
                targetProteinG = snapshot.version.targetProteinG,
                targetCarbsG = snapshot.version.targetCarbsG,
                targetFatG = snapshot.version.targetFatG,
                days = adjustedDays,
            ),
        )

        return Result(
            cheatId, true, versionId, confirmed.estimate.kcal, confirmed.estimateSummary,
            "Surplus di ${confirmed.estimate.kcal} kcal inserito nel serbatoio e distribuito sui prossimi ${reductions.size} giorni. Target ridotti gradualmente; residuo non ancora distribuito: ${remainingSurplus.coerceAtLeast(0.0).roundToInt()} kcal.",
            reductions.keys.map { "Target ${java.time.LocalDate.ofEpochDay(it)}: -${reductions.getValue(it)} kcal" },
        )
    }

    private fun validateInput(input: Input) {
        if (input.description.trim().isBlank()) throw AdjustmentException.NeedsInput(listOf("descrizione dello sgarro"))
        require(input.occurredAtEpochMillis > 0L)
    }

    fun inputFingerprint(input: Input): String = listOf(
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

    /** Uses the generated day's effective totals first; this preserves recovery-adjusted days. */
    private fun planTargets(day: FoodPlanDay, version: FoodPlanVersion): NutritionBusinessValidator.Targets? {
        val kcal = day.totalKcal?.toDouble() ?: version.targetKcal?.toDouble() ?: return null
        val protein = day.proteinG?.toDouble() ?: version.targetProteinG?.toDouble() ?: return null
        val carbs = day.carbsG?.toDouble() ?: version.targetCarbsG?.toDouble() ?: return null
        val fat = day.fatG?.toDouble() ?: version.targetFatG?.toDouble() ?: return null
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
        dietaryProfile: DietaryProfile,
    ): String = buildString {
        appendLine("CONFIRMED USER INTERPRETATION: ${confirmed.understoodFood}")
        appendLine("CONFIRMED ESTIMATE - MUST COPY EXACTLY into response.estimate:")
        appendLine("kcal=${confirmed.estimate.kcal}; proteinG=${confirmed.estimate.proteinG}; carbsG=${confirmed.estimate.carbsG}; fatG=${confirmed.estimate.fatG}; confidence=${confirmed.estimate.confidence}; notes=${confirmed.estimate.notes}")
        appendLine("DP:${dietaryProfile.toPromptCompact()}")
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
        val lockedKcal = lockedMeals.sumOf { (it.kcal ?: 0).toDouble() }
        val lockedProtein = lockedMeals.sumOf { (it.proteinG ?: 0f).toDouble() }
        val lockedCarbs = lockedMeals.sumOf { (it.carbsG ?: 0f).toDouble() }
        val lockedFat = lockedMeals.sumOf { (it.fatG ?: 0f).toDouble() }
        appendLine("Required replacement totals after locked meals + confirmed estimate: kcal=${targets.kcal - lockedKcal - confirmed.estimate.kcal}; proteinG=${targets.proteinG - lockedProtein - confirmed.estimate.proteinG}; carbsG=${targets.carbsG - lockedCarbs - confirmed.estimate.carbsG}; fatG=${targets.fatG - lockedFat - confirmed.estimate.fatG}")
        appendLine("Do not re-estimate the deviation. Use the confirmed estimate exactly. If balancing requires implausibly small meals, set adaptationPossible=false with no replacements.")
        appendLine("A/I/E/S in DP are hard constraints. D/P are soft preferences. Any replacement violating a hard constraint will be rejected locally.")
        appendLine("If adaptationPossible=1, return exactly one replacement per future meal. The sum of all replacement kcal/proteinG/carbsG/fatG must each fall within ±3% of the required replacement totals above; do not balance kcal while ignoring macros. Never move times, keep each replacement >=100 kcal, and count oils, sauces, condiments and caloric drinks.")
        appendLine("The app will independently verify locked meals + confirmed estimate + replacements within ±3% of the effective daily targets. agentValidation is advisory only.")
    }

    private fun mealLine(meal: FoodMeal) = "sortOrder=${meal.sortOrder}; time=${meal.timeMinutes}; type=${meal.type}; title=${meal.title}; kcal=${meal.kcal}; P=${meal.proteinG}; C=${meal.carbsG}; F=${meal.fatG}"

    private fun scaleMeal(meal: FoodMeal, scale: Double) = MealDraft(
        type = meal.type,
        title = meal.title,
        timeMinutes = meal.timeMinutes,
        kcal = (meal.kcal!! * scale).roundToInt().coerceAtLeast(1),
        proteinG = meal.proteinG?.times(scale.toFloat()),
        carbsG = meal.carbsG?.times(scale.toFloat()),
        fatG = meal.fatG?.times(scale.toFloat()),
        preparation = meal.preparation,
        ingredients = meal.ingredients.map { IngredientDraft(it.name, it.quantity, it.unit, it.displayDose.orEmpty(), it.weightState.orEmpty(), it.nutritionConfidence.orEmpty(), it.category.orEmpty()) },
    )

    private fun toDraft(day: FoodPlanDay) = DayDraft(
        dateEpochDay = day.dateEpochDay,
        totalKcal = day.totalKcal,
        proteinG = day.proteinG,
        carbsG = day.carbsG,
        fatG = day.fatG,
        targetKcal = day.targetKcal,
        targetProteinG = day.targetProteinG,
        targetCarbsG = day.targetCarbsG,
        targetFatG = day.targetFatG,
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
        private const val MAX_DAILY_REDUCTION_RATIO = 0.15
        private const val UNDERSTANDING_SYSTEM_PROMPT = """You are MyFitAI Nutrition Understanding Agent. Return only JSON matching the supplied schema. The JSON data value must contain exactly these three newline-separated records, in this order: CU1, U|understoodFood, and E|kcal|proteinG|carbsG|fatG|confidence|notes. Never return only the U record, never omit CU1 or E, and never put the records on one line. Your first job is to tell the user, in concise Italian, exactly what you understood they consumed, then estimate kcal and macros cautiously. If a nutrition-label image is attached, use only clearly visible values and never invent unreadable data. Nothing is saved at this stage; the user must be able to correct your understanding before confirmation."""

        private const val ADJUSTMENT_SYSTEM_PROMPT = """You are MyFitAI Nutrition Deviation Adaptation Agent. Return only JSON matching the supplied schema. The JSON data value must contain newline-separated pipe records and must start with CA1 on its own first line. Always include the required E estimate record, then exactly one A record, any R replacement records followed by their I ingredient records, and one final V validation record. Never omit CA1, never put CA1 on the same line as another record, and never return only E/A/R/I/V records without the CA1 header. The deviation interpretation and nutritional estimate were already shown to and confirmed by the user: copy that estimate exactly and do not reinterpret it. DP uses A=allergies, I=intolerances, E=excluded foods, D=disliked, P=preferred, S=diet style, N=notes. A/I/E/S are hard constraints and must never be violated; D/P are soft preferences. You may modify only meals explicitly listed as future meals for the same day. Never modify past meals or later days, never move meal times, and never use punitive fasting or extreme restriction. If a valid daily balance within ±3% cannot be achieved with reasonable future meals of at least 100 kcal each, set adaptationPossible=false and return no replacements. The app independently validates target arithmetic and every replacement ingredient against current hard constraints."""
    }
}
