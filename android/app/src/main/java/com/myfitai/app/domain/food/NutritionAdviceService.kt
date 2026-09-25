package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.domain.ai.AiUserContext
import kotlinx.coroutines.flow.first
import java.util.Locale

/** Ephemeral nutrition-only assistant. No conversation or answer is persisted. */
class NutritionAdviceService(
    private val aiRuntime: AiRuntimeGateway,
    private val profiles: UserProfileRepository,
    private val plans: MealPlanRepository,
    private val cheats: CheatEntryRepository,
    private val foodConsumptions: FoodConsumptionRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
) {
    data class Result(
        val accepted: Boolean,
        val answer: String,
        val suggestions: List<NutritionAdviceContract.Suggestion>,
        val assumptions: String,
        val providerLabel: String?,
    )

    suspend fun ask(question: String): Result {
        val normalized = question.trim()
        if (!isLocallyInScope(normalized)) return refused()

        val profileId = activeProfileStore.currentIdOrNull()
            ?: return Result(false, "Seleziona prima un profilo attivo.", emptyList(), "", null)
        val profile = profiles.get(profileId)
            ?: return Result(false, "Profilo non disponibile.", emptyList(), "", null)
        val dietaryProfile = DietaryProfile.parse(profile.dietaryPreferencesJson)

        val today = time.today()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val snapshot = plans.loadLatestSnapshot(profileId, monday.toEpochDay())
        val todayPlan = snapshot?.version?.days?.firstOrNull { it.dateEpochDay == today.toEpochDay() }
        val consumedRecords = snapshot?.let {
            foodConsumptions.forVersionDay(profileId, it.version.id, today.toEpochDay()).first()
        }.orEmpty()
        val consumed = FoodConsumptionMetrics.dayTotals(consumedRecords)
        val zone = time.zoneId
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        val deviations = cheats.between(profileId, dayStart, dayEnd).first()
        val actual = actualTotals(consumed, deviations)
        val now = time.currentTime()
        val minuteOfDay = now.hour * 60 + now.minute
        val targetKcal = todayPlan?.targetKcal ?: snapshot?.version?.targetKcal
        val targetProteinG = todayPlan?.targetProteinG ?: snapshot?.version?.targetProteinG
        val targetCarbsG = todayPlan?.targetCarbsG ?: snapshot?.version?.targetCarbsG
        val targetFatG = todayPlan?.targetFatG ?: snapshot?.version?.targetFatG

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(
                normalized,
                profile,
                dietaryProfile,
                snapshot,
                todayPlan,
                deviations,
                consumed,
                actual,
                targetKcal,
                targetProteinG,
                targetCarbsG,
                targetFatG,
                minuteOfDay,
                mealWindow(minuteOfDay),
            ),
            schemaName = NutritionAdviceContract.SCHEMA_NAME,
            schemaJson = NutritionAdviceContract.schemaJson,
            maxOutputTokens = 1_100,
            thinkingBudget = 0,
        )

        var parsed: NutritionAdviceContract.Response? = null
        val validated = runCatching {
            aiRuntime.execute(
                request = request,
                maxSchemaRetries = 1,
                businessValidator = { json -> runCatching {
                    val response = NutritionAdviceContract.parse(json)
                    NutritionAdviceContract.validateBusiness(response, dietaryProfile).getOrThrow()
                    parsed = response
                } },
            )
        }.getOrElse {
            return Result(false, "Consiglio nutrizionale non disponibile in questo momento.", emptyList(), "", null)
        }

        val response = parsed ?: runCatching {
            NutritionAdviceContract.parse(validated.jsonText).also {
                NutritionAdviceContract.validateBusiness(it, dietaryProfile).getOrThrow()
            }
        }.getOrElse {
            return Result(false, "Consiglio nutrizionale non disponibile in questo momento.", emptyList(), "", null)
        }
        if (!response.inScope) return refused()
        return Result(true, response.answer, response.suggestions, response.assumptions, "${validated.provider.name} · ${validated.model}")
    }

    private fun buildPrompt(
        question: String,
        profile: com.myfitai.app.data.local.entity.UserProfileEntity,
        dietaryProfile: DietaryProfile,
        snapshot: FoodPlanSnapshot?,
        todayPlan: FoodPlanDay?,
        deviations: List<com.myfitai.app.data.local.entity.CheatEntryEntity>,
        consumed: FoodConsumptionMetrics.Totals,
        actual: NutritionalTotals,
        targetKcal: Int?,
        targetProteinG: Float?,
        targetCarbsG: Float?,
        targetFatG: Float?,
        minuteOfDay: Int,
        mealWindow: String,
    ): String = buildString {
        appendLine("U:${AiUserContext.profileLine(profile, time.today())}")
        appendLine("Q:${clean(question)}")
        appendLine("N:$minuteOfDay;$mealWindow")
        appendLine("DP:${clean(dietaryProfile.toPromptCompact())}")
        appendLine("T:${targetKcal ?: "?"};${targetProteinG ?: "?"};${targetCarbsG ?: "?"};${targetFatG ?: "?"}")
        appendLine("C:${promptNumber(consumed.kcal)};${promptNumber(consumed.proteinG)};${promptNumber(consumed.carbsG)};${promptNumber(consumed.fatG)};${consumed.consumedCount};${consumed.skippedCount}")
        appendLine("A:${promptNumber(actual.kcal)};${promptNumber(actual.proteinG)};${promptNumber(actual.carbsG)};${promptNumber(actual.fatG)}")
        appendLine("R:${remaining(targetKcal, actual.kcal)};${remaining(targetProteinG, actual.proteinG)};${remaining(targetCarbsG, actual.carbsG)};${remaining(targetFatG, actual.fatG)}")
        todayPlan?.meals?.sortedBy { it.sortOrder }?.forEach { m ->
            val timing = if ((m.timeMinutes ?: Int.MAX_VALUE) > minuteOfDay) "UPCOMING" else "PAST"
            appendLine("M:$timing;${m.timeMinutes};${clean(m.type)};${clean(m.title)};${m.kcal};${m.proteinG};${m.carbsG};${m.fatG}")
        }
        deviations.forEach { d ->
            appendLine("D:${clean(d.description)};${d.estimatedKcal};${d.estimatedProteinG};${d.estimatedCarbsG};${d.estimatedFatG}")
        }
    }

    private fun clean(value: String?): String = AiCompactEnvelope.clean(value).replace(';', ',')

    private fun actualTotals(
        consumed: FoodConsumptionMetrics.Totals,
        deviations: List<com.myfitai.app.data.local.entity.CheatEntryEntity>,
    ) = NutritionalTotals(
        kcal = consumed.kcal + deviations.sumOf { it.estimatedKcal?.toDouble() ?: 0.0 },
        proteinG = consumed.proteinG + deviations.sumOf { it.estimatedProteinG?.toDouble() ?: 0.0 },
        carbsG = consumed.carbsG + deviations.sumOf { it.estimatedCarbsG?.toDouble() ?: 0.0 },
        fatG = consumed.fatG + deviations.sumOf { it.estimatedFatG?.toDouble() ?: 0.0 },
    )

    private fun promptNumber(value: Double) = String.format(Locale.US, "%.1f", value)
    private fun remaining(target: Int?, actual: Double) = target?.let { promptNumber(it - actual) } ?: "?"
    private fun remaining(target: Float?, actual: Double) = target?.let { promptNumber(it.toDouble() - actual) } ?: "?"

    private data class NutritionalTotals(
        val kcal: Double,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
    )

    private fun mealWindow(minuteOfDay: Int): String = when (minuteOfDay) {
        in 300..659 -> "AM"
        in 660..899 -> "LUNCH"
        in 900..1079 -> "PM"
        in 1080..1319 -> "DINNER"
        else -> "LATE"
    }

    private fun isLocallyInScope(value: String): Boolean {
        if (value.length < 3) return false
        val text = value.lowercase(Locale.ITALIAN)
        return NUTRITION_TERMS.any(text::contains)
    }

    private fun refused() = Result(false, OUT_OF_SCOPE_MESSAGE, emptyList(), "", null)

    companion object {
        const val OUT_OF_SCOPE_MESSAGE = "Posso rispondere solo a richieste di consiglio alimentare e nutrizionale."

        private val NUTRITION_TERMS = setOf(
            "mang", "aliment", "cibo", "pasto", "colazione", "pranzo", "cena", "spuntino",
            "calor", "kcal", "prote", "carbo", "grassi", "macro", "nutri", "dieta", "diet",
            "fame", "porzione", "gramm", "pizza", "pasta", "riso", "pane", "carne", "pollo",
            "pesce", "salmone", "uova", "yogurt", "latte", "formaggio", "frutta", "verdura",
            "dolce", "gelato", "snack", "bevanda", "bere", "bibita", "birra", "vino",
            "ristorante", "aperitivo", "fast food", "sushi", "integrale", "fibra", "sodio",
            "sale", "zuccher", "proteico", "vegetar", "vegano", "intoller", "allerg",
            "meal", "food"
        )

        private val SYSTEM_PROMPT = """Nutrition advice only. ${AiUserContext.INPUT_DESCRIPTION} Out of scope includes goal selection, deficit/surplus decisions, body-progress interpretation, BIA interpretation, and deviation/cheat compensation; for any of those use S=0 and exact answer "Posso rispondere solo a richieste di consiglio alimentare e nutrizionale.", no options. In scope: use app context only; M rows are planned meals, never consumed; C is the user's explicitly recorded plan consumption; D rows are recorded deviations; A is C plus D; R is the remaining daily target after actual intake. DP uses A=allergies, I=intolerances, E=excluded foods, D=disliked, P=preferred, S=diet style, N=notes. T and R are the effective daily calorie and macro targets, including any current plan adaptation. A/I/E/S are HARD constraints and must never be violated; D/P are soft preferences. Every O record MUST list in foodsCsv every food or ingredient implied by the option, comma-separated, so the app can independently reject hard-constraint violations. Do not treat planned food as eaten. For each option, propose a realistic next-meal or snack solution that fits the positive R kcal allowance and preserves the remaining protein/carbs/fat balance; do not spend the same calories twice. If R is low or negative, explain the constraint and return the lightest compliant alternatives instead of recommending an excess. If Q names a desired food, keep all options centered on it only when it does not violate a hard constraint; otherwise explain the conflict and suggest compliant alternatives. For generic requests use N meal window strongly, then R target fit, balance, calorie impact, practicality. No diagnosis, invented conditions, fasting, punitive restriction, or moral food labels. Return exactly 5 options, each with whole-meal kcal/P/C/F. Answer <=120 chars; title <=45; reason <=70; assumptions only if essential <=80. Plan changes only after app confirmation."""
    }
}
