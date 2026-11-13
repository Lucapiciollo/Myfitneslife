package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.data.local.entity.BiaAnalysisResultEntity
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.repository.BiaAnalysisResultRepository
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.ai.AiJobFailures
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.calculation.ProfileCalculationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate
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
    private val today: () -> LocalDate = LocalDate::now,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        return try {
            val profile = profiles.get(profileId) ?: error("Profilo non disponibile")
            val requestedId = params.getLong(KEY_MEASUREMENT_ID, NO_MEASUREMENT).takeIf { it > 0L }
            val automatic = params.getBoolean(KEY_AUTOMATIC, false)
            val builtReport = BiaAnalysisReport.build(bia.all(profileId).first(), requestedId)
            // Local gate BEFORE the paid call: nothing new to analyse, or no usable reading.
            val storedPayload = builtReport?.let { results.latestForMeasurement(profileId, it.latest.id)?.payloadJson }
            when (val decision = BiaAnalysisPolicy.decide(automatic, builtReport, storedPayload)) {
                is BiaAnalysisPolicy.Decision.Skip -> return skipped(profileId, automatic, decision.reason)
                BiaAnalysisPolicy.Decision.NoData -> {
                    if (automatic) return skipped(profileId, automatic, BiaAnalysisPolicy.SKIP_NO_DATA)
                    error("Nessuna rilevazione BIA con valori da analizzare")
                }
                BiaAnalysisPolicy.Decision.Run -> Unit
            }
            val report = checkNotNull(builtReport)
            val context = buildContext(report, profileId, profile)
            val result = service.analyze(
                BiaAnalysisService.Report(
                    inputPayload = context.prompt,
                    measurementCount = report.measurementCount,
                    safetyFlagActive = context.safetyFlag.active,
                    biologicalSex = profile.biologicalSex,
                    appScenarios = context.scenarios,
                    appCalories = context.caloriesText,
                ),
            )
            val resultId = results.insert(
                BiaAnalysisResultEntity(
                    profileId = profileId,
                    biaMeasurementId = report.latest.id,
                    createdAtEpochMillis = clock(),
                    provider = result.provider,
                    model = result.model,
                    payloadJson = payloadJson(result, report.latest, report.measurementCount, context.safetyFlag.active),
                ),
            )
            if (automatic) scheduler.onSuccess(profileId)
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
            AiJobFailures.rethrowIfRetryableOrCancelled(error)
            // A terminal failure of an automatic run must not restart on every app launch.
            if (params.getBoolean(KEY_AUTOMATIC, false)) runCatching { scheduler.deferAfterFailure(profileId) }
            AiJobOutcome.Failure("BIA_PROGRESS_COACH_FAILED:" + AiJobFailures.safeMessage(error, "Analisi BIA non disponibile"))
        }
    }

    /** Nothing was spent: the worker sees skipped and does not notify; automatic runs keep their cadence. */
    private fun skipped(profileId: Long, automatic: Boolean, reason: String): AiJobOutcome {
        if (automatic) scheduler.onSuccess(profileId)
        return AiJobOutcome.Success(
            Data.Builder().putBoolean(AiJobWorker.KEY_SKIPPED, true).putString(KEY_SKIP_REASON, reason).build(),
        )
    }

    private suspend fun buildContext(report: BiaAnalysisReport.Report, profileId: Long, profile: UserProfileEntity): BiaAnalysisReport.CoachContext {
        val planDays = plans.plans(profileId).first().flatMap { plan ->
            plans.loadLatestSnapshot(profileId, plan.weekStartEpochDay)?.version?.days.orEmpty().map { day ->
                BiaAnalysisReport.PlanDay(day.dateEpochDay, day.totalKcal, day.proteinG, day.carbsG, day.fatG)
            }
        }
        val calculation = calculations.profileSnapshot(profileId)?.calculation
        // One target for every day: the profile's, the same one shown by Home and Food.
        return BiaAnalysisReport.coachContext(
            profile = profile,
            today = today(),
            report = report,
            bodyHistory = body.all(profileId).first(),
            workoutHistory = workouts.all(profileId).first(),
            planDays = planDays,
            calculation = calculation,
        )
    }

    companion object {
        const val KEY_MEASUREMENT_ID = "measurement_id"
        const val KEY_RESULT_ID = "result_id"
        const val KEY_AUTOMATIC = "automatic"
        const val KEY_SKIP_REASON = "skip_reason"
        private const val NO_MEASUREMENT = -1L

        /** Fixed sentence stored by the app before T3 when the model gave no safety note; the UI no longer shows it. */
        const val DEFAULT_SAFETY_NOTE = "Analisi informativa, non sostituisce il parere di un medico o di un nutrizionista."

        /** The only parameter a manual analysis needs; everything else is read from Room. */
        fun params(measurementId: Long): Data = Data.Builder().putLong(KEY_MEASUREMENT_ID, measurementId).build()

        /**
         * @param measurement reading that was analysed: its id, date and a fingerprint of its values are stored so the
         *   result can later be matched to (or flagged obsolete against) the data it used.
         */
        fun payloadJson(
            result: BiaAnalysisService.Result,
            measurement: BiaMeasurementEntity? = null,
            measurementCount: Int? = null,
            safetyFlagActive: Boolean = false,
        ): String {
            val interpretation = result.interpretation
            return JSONObject()
                .put("contractVersion", BiaAnalysisPolicy.CONTRACT_VERSION)
                .apply {
                    measurement?.let {
                        put("biaMeasurementId", it.id)
                        put("measuredAtEpochMillis", it.measuredAtEpochMillis)
                        put("dataFingerprint", BiaAnalysisPolicy.fingerprint(it))
                    }
                    measurementCount?.let { put("measurementCount", it) }
                }
                .put("classification", interpretation.classification)
                .put("verdict", interpretation.verdict)
                .put("whatIsHappening", interpretation.whatIsHappening)
                .put("fatLoss", interpretation.fatLoss)
                .put("objective", interpretation.objective)
                .put("caloriesAndMacros", interpretation.caloriesAndMacros)
                // Calories and macros written by the app; absent in results saved before T4 (the UI then shows the model text as before).
                .apply { if (result.appCalories.isNotBlank()) put("appCalories", result.appCalories) }
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
                // The note is optional unless the safety flag was active. It is stored as written (blank when absent) and
                // the UI shows the section only when a note exists; results saved before T3 carry the fixed sentence
                // [DEFAULT_SAFETY_NOTE], which the UI treats as "no note".
                .put("safetyNote", interpretation.safetyNote)
                .put("safetyFlagActive", safetyFlagActive)
                .put("question", interpretation.question ?: "")
                .put("agentValidation", interpretation.agentValidation)
                .put("provider", result.provider)
                .put("model", result.model)
                .toString()
        }
    }
}
