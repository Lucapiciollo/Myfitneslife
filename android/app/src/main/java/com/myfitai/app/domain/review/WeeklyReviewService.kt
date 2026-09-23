package com.myfitai.app.domain.review

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.entity.WeeklyReviewEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.FoodConsumptionRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.WeeklyReviewRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.domain.ai.AiUserContext
import com.myfitai.app.domain.food.FoodPlanMetrics
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

class WeeklyReviewService(
    private val aiRuntime: AiRuntimeGateway,
    private val reviews: WeeklyReviewRepository,
    private val plans: MealPlanRepository,
    private val workouts: WorkoutRepository,
    private val cheats: CheatEntryRepository,
    private val bia: BiaRepository,
    private val bodyMeasurements: BodyMeasurementRepository,
    private val personalResponse: PersonalResponseService,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
    private val foodConsumptions: FoodConsumptionRepository? = null,
    private val profiles: UserProfileRepository? = null,
) {
    data class LocalMetrics(
        val weekStart: LocalDate, val weekEnd: LocalDate,
        val plannedAverageKcal: Int?, val targetKcal: Int?,
        val plannedAverageProteinG: Float?, val targetProteinG: Float?,
        val plannedAverageCarbsG: Float?, val targetCarbsG: Float?,
        val plannedAverageFatG: Float?, val targetFatG: Float?,
        val cheatCount: Int, val workoutCount: Int, val restDayCount: Int,
        val weightDeltaKg: Float?, val bodyFatDeltaPoints: Float?, val muscleMassDeltaKg: Float?, val waistDeltaCm: Float?,
        val plannedMealCount: Int,
        val consumedMealCount: Int,
        val skippedMealCount: Int,
        val trackingCoveragePercent: Int?,
        val adherencePercent: Int?,
        val consumedKcal: Int?,
        val hasPlan: Boolean,
    )

    data class Result(val entity: WeeklyReviewEntity, val response: WeeklyReviewContract.Response, val metrics: LocalMetrics, val provider: String, val model: String)

    sealed class ReviewException(message: String) : Exception(message) {
        class NeedsInput(val fields: List<String>) : ReviewException("NEEDS_INPUT: ${fields.joinToString()}")
        class WeekNotCompleted : ReviewException("WEEK_NOT_COMPLETED")
        class InvalidAiOutput(reason: String) : ReviewException(reason)
    }

    suspend fun loadExisting(weekStart: LocalDate): WeeklyReviewEntity? {
        val profileId = activeProfileStore.currentIdOrNull() ?: return null
        return reviews.getForWeek(profileId, monday(weekStart).toEpochDay())
    }

    suspend fun buildLocalMetrics(weekStart: LocalDate): LocalMetrics {
        val profileId = activeProfileStore.currentIdOrNull() ?: throw ReviewException.NeedsInput(listOf("profilo attivo"))
        return buildLocalMetrics(profileId, weekStart)
    }

    suspend fun buildLocalMetrics(profileId: Long, weekStart: LocalDate): LocalMetrics {
        val monday = monday(weekStart)
        val sunday = monday.plusDays(6)
        if (time.today().isBefore(sunday)) throw ReviewException.WeekNotCompleted()
        val zone = time.zoneId
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val plan = plans.loadLatestSnapshot(profileId, monday.toEpochDay())
        val weekWorkouts = workouts.between(profileId, from, to).first()
        val weekCheats = cheats.between(profileId, from, to).first()
        val allBia = bia.all(profileId).first()
        val allBody = bodyMeasurements.all(profileId).first()
        val weekBia = allBia.filter { it.measuredAtEpochMillis in from..to }
        val weekBody = allBody.filter { it.measuredAtEpochMillis in from..to }
        val previousBia = allBia.filter { it.measuredAtEpochMillis < from }
        val previousBody = allBody.filter { it.measuredAtEpochMillis < from }
        val days = plan?.version?.days.orEmpty()
        val plannedAverage = FoodPlanMetrics.weeklyAverage(days)
        val plannedItems = days.sumOf { it.meals.size + it.supplements.size }
        val plannedMeals = days.sumOf { it.meals.size }
        val consumptionRecords = if (plan != null && foodConsumptions != null) {
            foodConsumptions.all(profileId).first().filter {
                it.planVersionId == plan.version.id && it.plannedDateEpochDay in monday.toEpochDay()..sunday.toEpochDay()
            }
        } else emptyList()
        val consumption = WeeklyConsumptionMetrics.calculate(plannedMeals, plannedItems, consumptionRecords)
        return LocalMetrics(
            monday, sunday,
            plannedAverage.kcal?.roundToInt(), plan?.version?.targetKcal,
            plannedAverage.proteinG?.toFloat(), plan?.version?.targetProteinG,
            plannedAverage.carbsG?.toFloat(), plan?.version?.targetCarbsG,
            plannedAverage.fatG?.toFloat(), plan?.version?.targetFatG,
            weekCheats.size, weekWorkouts.count { !it.isRestDay }, weekWorkouts.count { it.isRestDay },
            delta(weekBia.mapNotNull { it.weightKg }, previousBia.mapNotNull { it.weightKg }),
            delta(weekBia.mapNotNull { it.bodyFatPercent }, previousBia.mapNotNull { it.bodyFatPercent }),
            delta(weekBia.mapNotNull { it.muscleMassKg }, previousBia.mapNotNull { it.muscleMassKg }),
            delta(weekBody.mapNotNull { it.waistCm }, previousBody.mapNotNull { it.waistCm }),
            consumption.plannedMealCount,
            consumption.consumedMealCount,
            consumption.skippedMealCount,
            consumption.trackingCoveragePercent,
            consumption.adherencePercent,
            consumption.consumedKcal,
            plan != null,
        )
    }

    suspend fun generate(weekStart: LocalDate): Result {
        val monday = monday(weekStart)
        val profileId = activeProfileStore.currentIdOrNull() ?: throw ReviewException.NeedsInput(listOf("profilo attivo"))
        return generate(profileId, monday)
    }

    suspend fun generate(profileId: Long, weekStart: LocalDate): Result {
        val monday = monday(weekStart)
        val metrics = buildLocalMetrics(profileId, monday)
        val historyContext = personalResponse.promptContext(lookbackDays = 56)
        val userContext = profiles?.get(profileId)?.let { AiUserContext.profileLine(it, time.today()) }.orEmpty()
        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(metrics, historyContext, userContext),
            schemaName = WeeklyReviewContract.SCHEMA_NAME,
            schemaJson = WeeklyReviewContract.schemaJson,
            maxOutputTokens = 700,
            thinkingBudget = 0,
        )
        var parsed: WeeklyReviewContract.Response? = null
        val validated = aiRuntime.execute(request, 1) { json ->
            runCatching {
                val response = WeeklyReviewContract.parse(json)
                WeeklyReviewContract.validateBusiness(response, monday.toEpochDay()).getOrThrow()
                parsed = response
            }
        }
        val response = parsed ?: runCatching { WeeklyReviewContract.parse(validated.jsonText) }
            .getOrElse { throw ReviewException.InvalidAiOutput("INVALID_COMPACT_PROTOCOL") }

        val structured = JSONObject()
            .put("weekStartEpochDay", response.weekStartEpochDay)
            .put("summary", response.summary)
            .put("observations", JSONArray(response.observations))
            .put("nextWeekGuidance", JSONArray(response.nextWeekGuidance))
            .put("agentValidation", JSONObject().put("valid", response.agentValidation.valid).put("notes", response.agentValidation.notes))
            .put("localMetrics", JSONObject()
                .put("plannedAverageKcal", metrics.plannedAverageKcal).put("targetKcal", metrics.targetKcal)
                .put("plannedAverageProteinG", metrics.plannedAverageProteinG).put("targetProteinG", metrics.targetProteinG)
                .put("plannedAverageCarbsG", metrics.plannedAverageCarbsG).put("targetCarbsG", metrics.targetCarbsG)
                .put("plannedAverageFatG", metrics.plannedAverageFatG).put("targetFatG", metrics.targetFatG)
                .put("cheatCount", metrics.cheatCount).put("workoutCount", metrics.workoutCount).put("restDayCount", metrics.restDayCount)
                .put("weightDeltaKg", metrics.weightDeltaKg).put("bodyFatDeltaPoints", metrics.bodyFatDeltaPoints)
                .put("muscleMassDeltaKg", metrics.muscleMassDeltaKg).put("waistDeltaCm", metrics.waistDeltaCm)
                .put("plannedMealCount", metrics.plannedMealCount).put("consumedMealCount", metrics.consumedMealCount)
                .put("skippedMealCount", metrics.skippedMealCount).put("trackingCoveragePercent", metrics.trackingCoveragePercent)
                .put("adherencePercent", metrics.adherencePercent).put("consumedKcal", metrics.consumedKcal))
            .toString()

        val entity = WeeklyReviewEntity(profileId = profileId, weekStartEpochDay = monday.toEpochDay(), createdAtEpochMillis = time.nowEpochMillis(), adherencePercent = metrics.adherencePercent?.toFloat(), summary = response.summary, structuredJson = structured)
        reviews.upsert(entity)
        val stored = reviews.getForWeek(profileId, monday.toEpochDay()) ?: entity
        return Result(stored, response, metrics, validated.provider.name, validated.model)
    }

    private fun buildPrompt(m: LocalMetrics, historyContext: String, userContext: String): String = buildString {
        if (userContext.isNotBlank()) appendLine("U:${userContext.replace('\n', ' ').replace('\r', ' ')}")
        appendLine("W:${m.weekStart.toEpochDay()}")
        appendLine("P:${m.plannedAverageKcal ?: "?"};${m.plannedAverageProteinG ?: "?"};${m.plannedAverageCarbsG ?: "?"};${m.plannedAverageFatG ?: "?"}")
        appendLine("T:${m.targetKcal ?: "?"};${m.targetProteinG ?: "?"};${m.targetCarbsG ?: "?"};${m.targetFatG ?: "?"}")
        appendLine("E:${m.cheatCount};${m.workoutCount};${m.restDayCount}")
        appendLine("C:${m.plannedMealCount};${m.consumedMealCount};${m.skippedMealCount};${m.trackingCoveragePercent ?: "?"};${m.adherencePercent ?: "?"};${m.consumedKcal ?: "?"}")
        appendLine("D:${m.weightDeltaKg ?: "?"};${m.bodyFatDeltaPoints ?: "?"};${m.muscleMassDeltaKg ?: "?"};${m.waistDeltaCm ?: "?"}")
        if (historyContext.isNotBlank()) appendLine("H:${AiCompactEnvelope.clean(historyContext)}")
    }

    companion object {
        private val SYSTEM_PROMPT = """Weekly nutrition review from recorded facts only. ${AiUserContext.INPUT_DESCRIPTION} P=planned averages, T=authoritative targets, E=deviations/workouts/rest, C=locally calculated meal tracking and consumption, D=observed body deltas. Never infer unrecorded consumption or adherence. Body changes are associative observations, never causes. No diagnosis/treatment. Next-week guidance practical, nutrition-only, never overrides T. Summary <=25 words; each O/G <=18 words; O 1..6; G 1..5."""
    }

    private fun monday(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
    private fun delta(values: List<Float>, previousValues: List<Float>): Float? = when {
        values.size >= 2 -> values.last() - values.first()
        values.size == 1 && previousValues.isNotEmpty() -> values.first() - previousValues.first()
        else -> null
    }
}
