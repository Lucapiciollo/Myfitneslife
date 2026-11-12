package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.data.local.entity.BiaAnalysisResultEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.repository.BiaAnalysisResultRepository
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.DailyActivityCheckInRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.ai.AiJobFailures
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.calculation.DayEnergyProvider
import com.myfitai.app.domain.calculation.OperationalNutritionMetrics
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
    /** Same sources as the Home: per-day energy and today's check-in. Null = no operational target is sent. */
    private val dayEnergy: DayEnergyProvider? = null,
    private val checkIns: DailyActivityCheckInRepository? = null,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        return try {
            val profile = profiles.get(profileId) ?: error("Profilo non disponibile")
            val requestedId = params.getLong(KEY_MEASUREMENT_ID, NO_MEASUREMENT).takeIf { it > 0L }
            val report = BiaAnalysisReport.build(bia.all(profileId).first(), requestedId)
                ?: error("Nessuna rilevazione BIA con valori da analizzare")
            val context = buildContext(report, profileId, profile)
            val result = service.analyze(
                BiaAnalysisService.Report(
                    inputPayload = context.prompt,
                    measurementCount = report.measurementCount,
                    safetyFlagActive = context.safetyFlag.active,
                    biologicalSex = profile.biologicalSex,
                    appScenarios = context.scenarios,
                ),
            )
            val resultId = results.insert(
                BiaAnalysisResultEntity(
                    profileId = profileId,
                    biaMeasurementId = report.latest.id,
                    createdAtEpochMillis = clock(),
                    provider = result.provider,
                    model = result.model,
                    payloadJson = payloadJson(result, context.safetyFlag.active),
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
            AiJobFailures.rethrowIfRetryableOrCancelled(error)
            AiJobOutcome.Failure("BIA_PROGRESS_COACH_FAILED:" + AiJobFailures.safeMessage(error, "Analisi BIA non disponibile"))
        }
    }

    private suspend fun buildContext(report: BiaAnalysisReport.Report, profileId: Long, profile: UserProfileEntity): BiaAnalysisReport.CoachContext {
        val planDays = plans.plans(profileId).first().flatMap { plan ->
            plans.loadLatestSnapshot(profileId, plan.weekStartEpochDay)?.version?.days.orEmpty().map { day ->
                BiaAnalysisReport.PlanDay(day.dateEpochDay, day.totalKcal, day.proteinG, day.carbsG, day.fatG)
            }
        }
        val calculation = calculations.profileSnapshot(profileId)?.calculation
        // Same calculation as the Home card: check-in of today + per-day program energy -> operational target.
        val now = today()
        val programDay = dayEnergy?.day(profileId, now)
        val operational = if (calculation == null && programDay == null) null else BiaAnalysisReport.OperationalTarget.from(
            OperationalNutritionMetrics.calculate(
                calculation,
                checkIns?.observeForDay(profileId, now.toEpochDay())?.first(),
                programDay,
            ),
        )
        return BiaAnalysisReport.coachContext(
            profile = profile,
            today = now,
            report = report,
            bodyHistory = body.all(profileId).first(),
            workoutHistory = workouts.all(profileId).first(),
            planDays = planDays,
            calculation = calculation,
            operational = operational,
        )
    }

    companion object {
        const val KEY_MEASUREMENT_ID = "measurement_id"
        const val KEY_RESULT_ID = "result_id"
        const val KEY_AUTOMATIC = "automatic"
        private const val NO_MEASUREMENT = -1L

        /** Fixed sentence stored by the app before T3 when the model gave no safety note; the UI no longer shows it. */
        const val DEFAULT_SAFETY_NOTE = "Analisi informativa, non sostituisce il parere di un medico o di un nutrizionista."

        /** The only parameter a manual analysis needs; everything else is read from Room. */
        fun params(measurementId: Long): Data = Data.Builder().putLong(KEY_MEASUREMENT_ID, measurementId).build()

        fun payloadJson(result: BiaAnalysisService.Result, safetyFlagActive: Boolean = false): String {
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
