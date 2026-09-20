package com.myfitai.app.domain.progress

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiUsageMetadata
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.max

/**
 * Multi-week AI interpretation of locally calculated body trends.
 * It never changes calorie/macro targets and never writes meal plans.
 */
class ProgressAnalysisService(
    private val aiRuntime: AiRuntimeGateway,
    private val calculations: ProfileCalculationService,
    private val profiles: UserProfileRepository,
    private val workouts: WorkoutRepository,
    private val cheats: CheatEntryRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val preferences: ProgressAnalysisPreferences,
    private val time: TimeProvider = SystemTimeProvider,
) {
    data class Result(
        val response: ProgressAnalysisCompactContract.Response,
        val provider: String,
        val model: String,
        val usage: AiUsageMetadata?,
        val executedAtEpochMillis: Long,
    )

    sealed class AnalysisException(message: String) : Exception(message) {
        class NeedsInput(val fields: List<String>) : AnalysisException("NEEDS_INPUT: ${fields.joinToString()}")
        class InvalidAiOutput(reason: String) : AnalysisException(reason)
    }

    suspend fun analyzeActive(): Result {
        val profileId = activeProfileStore.currentIdOrNull() ?: throw AnalysisException.NeedsInput(listOf("profilo attivo"))
        return analyze(profileId)
    }

    suspend fun analyze(profileId: Long): Result {
        val profile = profiles.get(profileId) ?: throw AnalysisException.NeedsInput(listOf("profilo"))
        val snapshot = calculations.profileSnapshot(profileId, time.today()) ?: throw AnalysisException.NeedsInput(listOf("dati profilo"))
        if (snapshot.latestBiaTimestamp == null) throw AnalysisException.NeedsInput(listOf("rilevazioni BIA"))
        if (snapshot.latestBodyMeasurementTimestamp == null) throw AnalysisException.NeedsInput(listOf("misure corporee"))

        val windowDays = max(MIN_ACTIVITY_WINDOW_DAYS, preferences.intervalWeeks * 7).coerceAtMost(MAX_ACTIVITY_WINDOW_DAYS)
        val zone = time.zoneId
        val to = time.nowEpochMillis()
        val from = to - java.util.concurrent.TimeUnit.DAYS.toMillis(windowDays.toLong())
        val periodWorkouts = workouts.between(profileId, from, to).first()
        val periodCheats = cheats.between(profileId, from, to).first()

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(
                goal = profile.goal,
                activity = profile.activityLevel,
                snapshot = snapshot,
                workoutCount = periodWorkouts.count { !it.isRestDay },
                restCount = periodWorkouts.count { it.isRestDay },
                cheatCount = periodCheats.size,
                windowDays = windowDays,
            ),
            schemaName = ProgressAnalysisCompactContract.SCHEMA_NAME,
            schemaJson = ProgressAnalysisCompactContract.schemaJson,
            maxOutputTokens = 450,
            thinkingBudget = 0,
        )

        var parsed: ProgressAnalysisCompactContract.Response? = null
        val validated = aiRuntime.execute(request, maxSchemaRetries = 1) { json ->
            runCatching {
                val response = ProgressAnalysisCompactContract.parse(json)
                require(response.valid) { "PROGRESS_AGENT_INVALID" }
                parsed = response
            }
        }
        val response = parsed ?: runCatching { ProgressAnalysisCompactContract.parse(validated.jsonText) }
            .getOrElse { throw AnalysisException.InvalidAiOutput("INVALID_PROGRESS_ANALYSIS_PROTOCOL") }
        if (!response.valid) throw AnalysisException.InvalidAiOutput("PROGRESS_AGENT_INVALID")

        val executedAt = time.nowEpochMillis()
        preferences.recordSuccess(profileId, executedAt, response, validated.provider.name, validated.model)
        return Result(response, validated.provider.name, validated.model, validated.usage, executedAt)
    }

    private fun buildPrompt(
        goal: String?,
        activity: String?,
        snapshot: ProfileCalculationService.Snapshot,
        workoutCount: Int,
        restCount: Int,
        cheatCount: Int,
        windowDays: Int,
    ): String = buildString {
        appendLine("P:${compact(goal)}|${compact(activity)}")
        val b = snapshot.biaMetrics
        appendLine("B0:${values(b.weight.baseline, b.bodyFat.baseline, b.muscleMass.baseline, b.skeletalMuscle.baseline, b.bodyWater.baseline, b.visceralFat.baseline)}")
        appendLine("B:${values(b.weight.current, b.bodyFat.current, b.muscleMass.current, b.skeletalMuscle.current, b.bodyWater.current, b.visceralFat.current)}")
        appendLine("BT:${values(b.weight.recentTrend.delta, b.bodyFat.recentTrend.delta, b.muscleMass.recentTrend.delta, b.skeletalMuscle.recentTrend.delta, b.bodyWater.recentTrend.delta, b.visceralFat.recentTrend.delta)}")
        val bm = snapshot.bodyMetrics
        appendLine("BM0:${bodyValues(bm) { it.baseline?.toDouble() }}")
        appendLine("BM:${bodyValues(bm) { it.current?.toDouble() }}")
        appendLine("BMT:${bodyValues(bm) { it.recentTrend.delta }}")
        appendLine("A:$workoutCount|$restCount|$cheatCount|$windowDays")
        appendLine("RS:${snapshot.recompositionState.name}")
    }

    private fun bodyValues(
        body: ProfileCalculationService.BodyMeasurementsSnapshot,
        pick: (ProfileCalculationService.MetricSnapshot) -> Double?,
    ): String = listOf(
        body.chest, body.waist, body.abdomen, body.shoulders, body.glutes,
        body.armLeft, body.armRight, body.thighLeft, body.thighRight, body.calfLeft, body.calfRight,
    ).joinToString("|") { fmtOrUnknown(pick(it)) }

    private fun values(vararg values: Float?): String = values.joinToString("|") { it?.let { v -> fmt(v.toDouble()) } ?: "?" }
    private fun values(vararg values: Double?): String = values.joinToString("|") { fmtOrUnknown(it) }
    private fun fmtOrUnknown(value: Double?): String = value?.let(::fmt) ?: "?"
    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun compact(value: String?): String = value.orEmpty().replace('|', '/').replace('\n', ' ').trim().ifBlank { "?" }

    companion object {
        private const val MIN_ACTIVITY_WINDOW_DAYS = 28
        private const val MAX_ACTIVITY_WINDOW_DAYS = 84
        private val SYSTEM_PROMPT = """
MyFitAI ProgressAnalysisAgent. Interpret only supplied historical signals; never calculate or change calorie/macro targets and never generate a diet. Output ONLY the compact protocol below in the JSON data envelope.
${ProgressAnalysisCompactContract.PROTOCOL}
Input: P=goal|activity. B0/B/BT order weightKg|bodyFatPct|muscleMassKg|skeletalMuscleKg|bodyWaterPct|visceralFat and means baseline/current/recent trend delta. BM0/BM/BMT order chest|waist|abdomen|shoulders|glutes|armLeft|armRight|thighLeft|thighRight|calfLeft|calfRight. A=workouts|restDays|registeredDeviations|windowDays. `?`=unavailable. RS is the local recomposition classification and is context, not proof.
Classify from multiple coherent signals, not weight alone. PR=positive recomposition, ST=stable, WL=weight loss without clear muscle-risk signal, MR=weight loss with possible muscle-risk signal, NT=negative trend, ID=insufficient/inconsistent data. P codes: WT weight, BF body fat, MU muscle, WA waist, AB abdomen, LM limb measures, TR training, DV registered deviations, BC cross-signal body coherence. Direction F/U/X=favorable/unfavorable/uncertain. Confidence L/M/H.
BIA and circumferences are estimates/observations: distinguish association from causality, never diagnose disease/dehydration/edema/muscle loss, and lower confidence when signals conflict. Registered deviations are not proof of total adherence or intake. Do not infer consumption from planned meals. Max 6 P records. S <=18 words, factual and useful. V=1 unless the supplied data are internally unusable; notes <=8 words. No markdown, no text outside records.
""".trimIndent()
    }
}
