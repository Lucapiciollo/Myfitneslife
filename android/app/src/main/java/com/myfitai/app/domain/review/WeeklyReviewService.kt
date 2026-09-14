package com.myfitai.app.domain.review

import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.entity.WeeklyReviewEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.WeeklyReviewRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class WeeklyReviewService(
    private val aiRuntime: AiRuntimeService,
    private val reviews: WeeklyReviewRepository,
    private val plans: MealPlanRepository,
    private val workouts: WorkoutRepository,
    private val cheats: CheatEntryRepository,
    private val bia: BiaRepository,
    private val bodyMeasurements: BodyMeasurementRepository,
    private val personalResponse: PersonalResponseService,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
) {
    data class LocalMetrics(
        val weekStart: LocalDate,
        val weekEnd: LocalDate,
        val plannedAverageKcal: Int?,
        val targetKcal: Int?,
        val plannedAverageProteinG: Float?,
        val targetProteinG: Float?,
        val plannedAverageCarbsG: Float?,
        val targetCarbsG: Float?,
        val plannedAverageFatG: Float?,
        val targetFatG: Float?,
        val cheatCount: Int,
        val workoutCount: Int,
        val restDayCount: Int,
        val weightDeltaKg: Float?,
        val bodyFatDeltaPoints: Float?,
        val muscleMassDeltaKg: Float?,
        val waistDeltaCm: Float?,
        val hasPlan: Boolean,
    )

    data class Result(
        val entity: WeeklyReviewEntity,
        val response: WeeklyReviewContract.Response,
        val metrics: LocalMetrics,
        val provider: String,
        val model: String,
    )

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
        val monday = monday(weekStart)
        val sunday = monday.plusDays(6)
        if (!sunday.isBefore(time.today())) throw ReviewException.WeekNotCompleted()
        val profileId = activeProfileStore.currentIdOrNull()
            ?: throw ReviewException.NeedsInput(listOf("profilo attivo"))
        val zone = time.zoneId
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        val plan = plans.loadLatestSnapshot(profileId, monday.toEpochDay())
        val weekWorkouts = workouts.between(profileId, from, to).first()
        val weekCheats = cheats.between(profileId, from, to).first()
        val weekBia = bia.between(profileId, from, to).first()
        val weekBody = bodyMeasurements.between(profileId, from, to).first()

        val days = plan?.version?.days.orEmpty()
        return LocalMetrics(
            weekStart = monday,
            weekEnd = sunday,
            plannedAverageKcal = days.mapNotNull { it.totalKcal }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            targetKcal = plan?.version?.targetKcal,
            plannedAverageProteinG = averageOrNull(days.mapNotNull { it.proteinG }),
            targetProteinG = plan?.version?.targetProteinG,
            plannedAverageCarbsG = averageOrNull(days.mapNotNull { it.carbsG }),
            targetCarbsG = plan?.version?.targetCarbsG,
            plannedAverageFatG = averageOrNull(days.mapNotNull { it.fatG }),
            targetFatG = plan?.version?.targetFatG,
            cheatCount = weekCheats.size,
            workoutCount = weekWorkouts.count { !it.isRestDay },
            restDayCount = weekWorkouts.count { it.isRestDay },
            weightDeltaKg = delta(weekBia.mapNotNull { it.weightKg }),
            bodyFatDeltaPoints = delta(weekBia.mapNotNull { it.bodyFatPercent }),
            muscleMassDeltaKg = delta(weekBia.mapNotNull { it.muscleMassKg }),
            waistDeltaCm = delta(weekBody.mapNotNull { it.waistCm }),
            hasPlan = plan != null,
        )
    }

    suspend fun generate(weekStart: LocalDate): Result {
        val monday = monday(weekStart)
        val profileId = activeProfileStore.currentIdOrNull()
            ?: throw ReviewException.NeedsInput(listOf("profilo attivo"))
        val metrics = buildLocalMetrics(monday)
        val historyContext = personalResponse.promptContext(lookbackDays = 56)

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(metrics, historyContext),
            schemaName = WeeklyReviewContract.SCHEMA_NAME,
            schemaJson = WeeklyReviewContract.schemaJson,
            maxOutputTokens = 4_000,
        )

        var parsed: WeeklyReviewContract.Response? = null
        val validated = aiRuntime.execute(
            request = request,
            maxSchemaRetries = 1,
            businessValidator = { json ->
                runCatching {
                    val response = WeeklyReviewContract.parse(json)
                    WeeklyReviewContract.validateBusiness(response, monday.toEpochDay()).getOrThrow()
                    parsed = response
                }
            },
        )
        val response = parsed ?: runCatching { WeeklyReviewContract.parse(validated.jsonText) }
            .getOrElse { throw ReviewException.InvalidAiOutput("INVALID_SCHEMA") }

        val structured = JSONObject()
            .put("weekStartEpochDay", response.weekStartEpochDay)
            .put("summary", response.summary)
            .put("observations", JSONArray(response.observations))
            .put("nextWeekGuidance", JSONArray(response.nextWeekGuidance))
            .put("agentValidation", JSONObject().put("valid", response.agentValidation.valid).put("notes", response.agentValidation.notes))
            .put("localMetrics", JSONObject()
                .put("plannedAverageKcal", metrics.plannedAverageKcal)
                .put("targetKcal", metrics.targetKcal)
                .put("plannedAverageProteinG", metrics.plannedAverageProteinG)
                .put("targetProteinG", metrics.targetProteinG)
                .put("plannedAverageCarbsG", metrics.plannedAverageCarbsG)
                .put("targetCarbsG", metrics.targetCarbsG)
                .put("plannedAverageFatG", metrics.plannedAverageFatG)
                .put("targetFatG", metrics.targetFatG)
                .put("cheatCount", metrics.cheatCount)
                .put("workoutCount", metrics.workoutCount)
                .put("restDayCount", metrics.restDayCount)
                .put("weightDeltaKg", metrics.weightDeltaKg)
                .put("bodyFatDeltaPoints", metrics.bodyFatDeltaPoints)
                .put("muscleMassDeltaKg", metrics.muscleMassDeltaKg)
                .put("waistDeltaCm", metrics.waistDeltaCm)
            )
            .toString()

        val entity = WeeklyReviewEntity(
            profileId = profileId,
            weekStartEpochDay = monday.toEpochDay(),
            createdAtEpochMillis = time.nowEpochMillis(),
            adherencePercent = null,
            summary = response.summary,
            structuredJson = structured,
        )
        reviews.upsert(entity)
        val stored = reviews.getForWeek(profileId, monday.toEpochDay()) ?: entity
        return Result(stored, response, metrics, validated.provider.name, validated.model)
    }

    private fun buildPrompt(metrics: LocalMetrics, historyContext: String): String = buildString {
        appendLine("Review the completed week ${metrics.weekStart} to ${metrics.weekEnd}.")
        appendLine("IMPORTANT: actual meal adherence is NOT recorded. Never invent adherence, consumed calories or compliance percentages.")
        appendLine("Planned nutrition metrics only: avgKcal=${metrics.plannedAverageKcal ?: "n/a"}, targetKcal=${metrics.targetKcal ?: "n/a"}, avgProteinG=${metrics.plannedAverageProteinG ?: "n/a"}, targetProteinG=${metrics.targetProteinG ?: "n/a"}, avgCarbsG=${metrics.plannedAverageCarbsG ?: "n/a"}, targetCarbsG=${metrics.targetCarbsG ?: "n/a"}, avgFatG=${metrics.plannedAverageFatG ?: "n/a"}, targetFatG=${metrics.targetFatG ?: "n/a"}.")
        appendLine("Recorded deviations=${metrics.cheatCount}; workouts=${metrics.workoutCount}; restDays=${metrics.restDayCount}.")
        appendLine("Within-week observed body deltas, when enough measurements exist: weightKg=${metrics.weightDeltaKg ?: "n/a"}, bodyFatPoints=${metrics.bodyFatDeltaPoints ?: "n/a"}, muscleMassKg=${metrics.muscleMassDeltaKg ?: "n/a"}, waistCm=${metrics.waistDeltaCm ?: "n/a"}.")
        if (historyContext.isNotBlank()) appendLine(historyContext)
        appendLine("Use cautious associative language only. Do not claim that food, workouts, deviations or the plan caused body changes. Guidance for next week must remain practical and must not override local numerical targets.")
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are MyFitAI PlanReviewAgent. Return only JSON matching the supplied schema. Review only recorded facts. Distinguish planned nutrition from actual consumption. Never fabricate adherence. Body changes are observations, not proof of causality. Do not diagnose, prescribe treatment, or make unsupported physiological claims. The app remains authoritative for all numerical nutrition targets."""
    }

    private fun monday(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
    private fun averageOrNull(values: List<Float>): Float? = values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    private fun delta(values: List<Float>): Float? = if (values.size >= 2) values.last() - values.first() else null
}
