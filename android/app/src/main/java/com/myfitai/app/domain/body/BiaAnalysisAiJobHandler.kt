package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.data.local.entity.BiaAnalysisResultEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.repository.BiaAnalysisResultRepository
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.calculation.ProfileCalculationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the BIA Progress Coach. Input comes from Room (see [BiaAnalysisReport]) and the result
 * is persisted here, so it survives the Activity being destroyed. Only small identifiers
 * travel through WorkManager `Data`, which is limited to 10 KB.
 */
class BiaAnalysisAiJobHandler(
    private val service: BiaAnalysisService,
    private val profiles: UserProfileRepository,
    private val bia: BiaRepository,
    private val body: BodyMeasurementRepository,
    private val workouts: WorkoutRepository,
    private val plans: MealPlanRepository,
    private val calculations: ProfileCalculationService,
    private val scheduler: BiaProgressCoachScheduler,
    private val results: BiaAnalysisResultRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        return try {
            val profile = profiles.get(profileId) ?: error("Profilo non disponibile")
            val requestedId = params.getLong(KEY_MEASUREMENT_ID, NO_MEASUREMENT).takeIf { it > 0L }
            val report = BiaAnalysisReport.build(bia.all(profileId).first(), requestedId)
                ?: error("Nessuna rilevazione BIA con valori da analizzare")
            val result = service.analyze(
                BiaAnalysisService.Report(
                    inputPayload = buildPrompt(report, profileId, profile),
                    measurementCount = report.measurementCount,
                ),
            )
            val resultId = results.insert(
                BiaAnalysisResultEntity(
                    profileId = profileId,
                    biaMeasurementId = report.latest.id,
                    createdAtEpochMillis = clock(),
                    provider = result.provider,
                    model = result.model,
                    payloadJson = payloadJson(result),
                ),
            )
            if (params.getBoolean(KEY_AUTOMATIC, false)) scheduler.onSuccess(profileId)
            AiJobOutcome.Success(
                Data.Builder()
                    .putLong(KEY_RESULT_ID, resultId)
                    .putLong(KEY_MEASUREMENT_ID, report.latest.id)
                    .putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}")
                    .build(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AiJobOutcome.Failure("BIA_PROGRESS_COACH_FAILED:${(error.message ?: "Analisi BIA non disponibile").take(MAX_ERROR_CHARS)}")
        }
    }

    private suspend fun buildPrompt(report: BiaAnalysisReport.Report, profileId: Long, profile: UserProfileEntity): String = buildString {
        val bodyHistory = body.all(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val biaHistory = bia.all(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val workoutHistory = workouts.all(profileId).first().sortedBy { it.startedAtEpochMillis }
        val snapshot = calculations.profileSnapshot(profileId)
        val planLines = plans.plans(profileId).first().mapNotNull { plan ->
            plans.loadLatestSnapshot(profileId, plan.weekStartEpochDay)?.let { loaded ->
                loaded.version.days.sortedBy { it.dateEpochDay }.joinToString(";") { day ->
                    "date=${day.dateEpochDay},kcal=${day.totalKcal ?: "?"},p=${day.proteinG ?: "?"},c=${day.carbsG ?: "?"},f=${day.fatG ?: "?"}"
                }
            }
        }
        appendLine("PROFILO_UTENTE:")
        appendLine("sex=${profile.biologicalSex ?: "?"}|birthDate=${profile.birthDateEpochDay ?: "?"}|heightCm=${profile.heightCm ?: "?"}|weightKg=${profile.currentWeightKg ?: "?"}|goal=${profile.goal ?: "?"}|activity=${profile.activityLevel ?: "?"}|wake=${profile.wakeTimeMinutes ?: "?"}|sleep=${profile.sleepTimeMinutes ?: "?"}|dietary=${profile.dietaryPreferencesJson ?: "?"}")
        appendLine("OBIETTIVO:${profile.goal ?: "?"}")
        appendLine("BIA_CORRENTE:date=${BiaHistoryImportContract.dayKey(report.latest.measuredAtEpochMillis)},${BiaAnalysisReport.valuesForPrompt(report.current)}")
        appendLine("STORICO_BIA:${BiaAnalysisReport.historyForPrompt(report.history)}")
        appendLine("DELTA_PRECEDENTE:${BiaAnalysisReport.valuesForPrompt(report.previousDelta)}")
        appendLine("CONDIZIONI_MISURAZIONE:${report.conditions}")
        appendLine("NOTE_UTENTE:${report.latest.notes?.takeIf { it.isNotBlank() }?.replace('\n', ' ') ?: "?"}")
        appendLine("MISURE_CORPOREE:${bodyHistory.joinToString(";") { "date=${it.measuredAtEpochMillis},weight=${it.weightKg ?: "?"},waist=${it.waistCm ?: "?"},abdomen=${it.abdomenCm ?: "?"},hips=${it.hipsCm ?: "?"},chest=${it.chestCm ?: "?"},arms=${it.armLeftCm ?: "?"}/${it.armRightCm ?: "?"},thighs=${it.thighLeftCm ?: "?"}/${it.thighRightCm ?: "?"}" }.ifBlank { "?" }}")
        appendLine("STORICO_PESO:${(biaHistory.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } } + bodyHistory.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } }).sortedBy { it.first }.joinToString(";") { "${it.first}=${it.second}" }.ifBlank { "?" }}")
        appendLine("ALLENAMENTI:${workoutHistory.joinToString(";") { "date=${it.startedAtEpochMillis},type=${it.type},title=${it.title},duration=${it.durationMinutes ?: "?"},rest=${it.isRestDay}" }.ifBlank { "?" }}")
        appendLine("PRESTAZIONI_E_FORZA:?")
        appendLine("ALIMENTAZIONE:${planLines.joinToString("|").ifBlank { "?" }}")
        appendLine("CALORIE_E_MACRO:${snapshot?.calculation?.let { "bmr=${it.bmrKcal ?: "?"},tdee=${it.tdeeKcal ?: "?"},target=${it.targetKcal ?: "?"},p=${it.proteinG ?: "?"},c=${it.carbsG ?: "?"},f=${it.fatG ?: "?"}" } ?: "?"}")
        appendLine("ATTIVITA_E_PASSI:?")
        appendLine("INTEGRAZIONE:?")
        appendLine("SONNO_E_RECUPERO:wake=${profile.wakeTimeMinutes ?: "?"},sleep=${profile.sleepTimeMinutes ?: "?"}")
    }

    companion object {
        const val KEY_MEASUREMENT_ID = "measurement_id"
        const val KEY_RESULT_ID = "result_id"
        const val KEY_AUTOMATIC = "automatic"
        private const val NO_MEASUREMENT = -1L
        private const val MAX_ERROR_CHARS = 300

        /** The only parameter a manual analysis needs; everything else is read from Room. */
        fun params(measurementId: Long): Data = Data.Builder().putLong(KEY_MEASUREMENT_ID, measurementId).build()

        fun payloadJson(result: BiaAnalysisService.Result): String {
            val interpretation = result.interpretation
            return JSONObject()
                .put("classification", interpretation.classification)
                .put("verdict", interpretation.verdict)
                .put("whatIsHappening", interpretation.whatIsHappening)
                .put("fatLoss", interpretation.fatLoss)
                .put("objective", interpretation.objective)
                .put("caloriesAndMacros", interpretation.caloriesAndMacros)
                .put("nextCheck", interpretation.nextCheck)
                .put("reliability", interpretation.reliability)
                .put("reliabilityReason", interpretation.reliabilityReason)
                .put("comparisons", JSONArray().apply {
                    interpretation.comparisons.forEach { comparison ->
                        put(
                            JSONObject().put("indicator", comparison.indicator).put("previous", comparison.previous)
                                .put("current", comparison.current).put("difference", comparison.difference)
                                .put("interpretation", comparison.interpretation).put("reliability", comparison.reliability),
                        )
                    }
                })
                .put("historical", JSONArray().apply {
                    interpretation.historical.forEach { put(JSONObject().put("period", it.period).put("summary", it.summary)) }
                })
                .put("scenarios", JSONArray().apply {
                    interpretation.scenarios.forEach { item ->
                        put(
                            JSONObject().put("label", item.label).put("targetBodyFat", item.targetBodyFat)
                                .put("targetWeight", item.targetWeight).put("fatToLose", item.fatToLose)
                                .put("assumption", item.assumption).put("reliability", item.reliability),
                        )
                    }
                })
                .put("positives", JSONArray(interpretation.positives))
                .put("monitor", JSONArray(interpretation.monitor))
                .put("actions", JSONArray(interpretation.actions))
                .put("safetyNote", interpretation.safetyNote)
                .put("question", interpretation.question ?: "")
                .put("agentValidation", interpretation.agentValidation)
                .put("provider", result.provider)
                .put("model", result.model)
                .toString()
        }
    }
}
