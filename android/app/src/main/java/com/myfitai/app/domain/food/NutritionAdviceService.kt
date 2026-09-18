package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import java.util.Locale

/** Ephemeral nutrition-only assistant. No conversation or answer is persisted. */
class NutritionAdviceService(
    private val aiRuntime: AiRuntimeGateway,
    private val profiles: UserProfileRepository,
    private val plans: MealPlanRepository,
    private val cheats: CheatEntryRepository,
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
        val zone = time.zoneId
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        val deviations = cheats.between(profileId, dayStart, dayEnd).first()
        val now = time.currentTime()
        val minuteOfDay = now.hour * 60 + now.minute

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(normalized, dietaryProfile, snapshot, todayPlan, deviations, minuteOfDay, mealWindow(minuteOfDay)),
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
        dietaryProfile: DietaryProfile,
        snapshot: FoodPlanSnapshot?,
        todayPlan: FoodPlanDay?,
        deviations: List<com.myfitai.app.data.local.entity.CheatEntryEntity>,
        minuteOfDay: Int,
        mealWindow: String,
    ): String = buildString {
        appendLine("Q:${clean(question)}")
        appendLine("N:$minuteOfDay;$mealWindow")
        appendLine("DP:${clean(dietaryProfile.toPromptCompact())}")
        snapshot?.version?.let { appendLine("T:${it.targetKcal};${it.targetProteinG};${it.targetCarbsG};${it.targetFatG}") }
        todayPlan?.meals?.sortedBy { it.sortOrder }?.forEach { m ->
            appendLine("M:${m.timeMinutes};${clean(m.type)};${clean(m.title)};${m.kcal};${m.proteinG};${m.carbsG};${m.fatG}")
        }
        deviations.forEach { d ->
            appendLine("D:${clean(d.description)};${d.estimatedKcal};${d.estimatedProteinG};${d.estimatedCarbsG};${d.estimatedFatG}")
        }
    }

    private fun clean(value: String?): String = AiCompactEnvelope.clean(value).replace(';', ',')

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

        private const val SYSTEM_PROMPT = """Nutrition advice only. Out of scope includes goal selection, deficit/surplus decisions, body-progress interpretation, BIA interpretation, and deviation/cheat compensation; for any of those use S=0 and exact answer "Posso rispondere solo a richieste di consiglio alimentare e nutrizionale.", no options. In scope: use app context only; M rows are planned, not consumed; D rows are recorded deviations. DP uses A=allergies, I=intolerances, E=excluded foods, D=disliked, P=preferred, S=diet style, N=notes. A/I/E/S are HARD constraints and must never be violated; D/P are soft preferences. Every O record MUST list in foodsCsv every food or ingredient implied by the option, comma-separated, so the app can independently reject hard-constraint violations. No diagnosis, invented conditions, fasting or punitive restriction. Return exactly 5 options, each with whole-meal kcal/P/C/F. If Q names a desired food, keep all options centered on it only when it does not violate a hard constraint; otherwise explain the conflict and suggest compliant alternatives. For generic requests use N meal window strongly, then target fit, balance, calorie impact, practicality. Answer <=120 chars; title <=45; reason <=70; assumptions only if essential <=80. No moral food labels. Plan changes only after app confirmation."""
    }
}
