package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.calculation.ProfileCalculationService
import java.time.LocalDate
import java.util.Locale

/**
 * Independent qualitative gate for full weekly plans.
 * Hard arithmetic/structural validation must already have succeeded before this service is called.
 */
class PlanReviewService(
    private val aiRuntime: AiRuntimeGateway,
) {
    data class WorkoutSignal(
        val dayOffset: Int,
        val timeMinutes: Int,
        val type: String,
        val durationMinutes: Int,
        val isRestDay: Boolean,
    )

    data class Context(
        val monday: LocalDate,
        val targets: NutritionBusinessValidator.Targets,
        val goal: String?,
        val activityLevel: String?,
        val wakeTimeMinutes: Int?,
        val sleepTimeMinutes: Int?,
        val dietaryPreferences: String?,
        val sportsMode: SportsNutritionClassifier.Mode,
        val snapshot: ProfileCalculationService.Snapshot,
        val workouts: List<WorkoutSignal>,
    )

    suspend fun review(
        plan: NutritionPlanContract.Response,
        context: Context,
    ): PlanReviewCompactContract.Result {
        var parsed: PlanReviewCompactContract.Result? = null
        val response = aiRuntime.execute(
            request = AiStructuredRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildPrompt(plan, context),
                schemaName = PlanReviewCompactContract.SCHEMA_NAME,
                schemaJson = PlanReviewCompactContract.schemaJson,
                maxOutputTokens = 700,
                thinkingBudget = 0,
            ),
            maxSchemaRetries = 1,
            businessValidator = { json ->
                runCatching {
                    parsed = PlanReviewCompactContract.parse(json)
                }
            },
        )
        return parsed ?: PlanReviewCompactContract.parse(response.jsonText)
    }

    private fun buildPrompt(
        plan: NutritionPlanContract.Response,
        context: Context,
    ): String = buildString {
        appendLine("W|${context.monday.toEpochDay()}")
        appendLine("T|${context.targets.kcal.toInt()}|${f(context.targets.proteinG)}|${f(context.targets.carbsG)}|${f(context.targets.fatG)}")
        appendLine("P|${c(context.goal)}|${c(context.activityLevel)}|${context.wakeTimeMinutes ?: "-"}|${context.sleepTimeMinutes ?: "-"}|${context.sportsMode.name}")
        appendLine("DP|${c(context.dietaryPreferences)}")
        val s = context.snapshot
        appendLine("BC|${s.recompositionState.name}|${f(s.weightTrend.delta)}|${f(s.bodyFatTrend.delta)}|${f(s.muscleMassTrend.delta)}|${f(s.bodyMetrics.waist.recentTrend.delta)}|${f(s.bodyMetrics.abdomen.recentTrend.delta)}")
        context.workouts.sortedWith(compareBy<WorkoutSignal> { it.dayOffset }.thenBy { it.timeMinutes }).forEach { w ->
            appendLine("WO|${w.dayOffset}|${w.timeMinutes}|${c(w.type)}|${w.durationMinutes}|${if (w.isRestDay) 1 else 0}")
        }
        plan.days.sortedBy { it.dateEpochDay }.forEach { day ->
            val offset = (day.dateEpochDay - context.monday.toEpochDay()).toInt()
            appendLine("D|$offset|${day.totalKcal}|${f(day.proteinG)}|${f(day.carbsG)}|${f(day.fatG)}")
            day.meals.sortedBy { it.timeMinutes }.forEach { meal ->
                val foods = meal.ingredients
                    .map { c(it.name) }
                    .filter { it != "-" }
                    .joinToString(",")
                appendLine("M|${c(meal.type)}|${meal.timeMinutes}|${meal.kcal}|${f(meal.proteinG)}|${f(meal.carbsG)}|${f(meal.fatG)}|$foods")
            }
            day.supplements.forEach { supplement ->
                appendLine("S|${c(supplement.kind)}|${supplement.timeMinutes}|${supplement.kcal}|${f(supplement.proteinG)}|${c(supplement.name)}")
            }
        }
    }

    private fun c(value: String?): String = value.orEmpty()
        .replace('|', '/')
        .replace(',', '/')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .ifBlank { "-" }

    private fun f(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
    private fun f(value: Float?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "-"

    companion object {
        private val SYSTEM_PROMPT = """
Independent MyFitAI nutrition-plan quality reviewer. The app already validated hard structure, arithmetic, daily totals and target tolerance; DO NOT redo arithmetic, recalculate targets, change deficit/macros, or generate a replacement plan. Review only qualitative quality from the compact input: meal timing vs wake/sleep/workouts, protein/macro distribution, genuine weekly variety from ingredient sets, declared food constraints, cautious digestive load, training/rest coherence, fruit/vegetable variety, and consistency with BC body trends. BC is observational only: no diagnosis and no causal claims. Do not reject solely for pizza/sushi/gelato when compatible with the plan.
Output ONLY JSON envelope whose data follows:
${PlanReviewCompactContract.PROTOCOL}
Codes: PD protein distribution; MT meal timing; PW excessive pre-workout fat/fiber/volume; WV weak weekly variety; DM substantially duplicated meal; FC declared food constraint; DC concrete digestive-comfort concern; TC training/rest coherence; BC body-context coherence; FV fruit/vegetable variety. Severity I/W never blocks; M/C blocks. S=A only with no M/C. S=R requires at least one M/C. S=N only when essential review context is missing. Keep issues minimal: report only actionable findings; no prose.
""".trimIndent()
    }
}
