package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.calculation.ProfileCalculationService
import kotlinx.coroutines.flow.first
import org.json.JSONObject

class BiaAnalysisAiJobHandler(
    private val service: BiaAnalysisService,
    private val profiles: UserProfileRepository,
    private val bia: BiaRepository,
    private val body: BodyMeasurementRepository,
    private val workouts: WorkoutRepository,
    private val plans: MealPlanRepository,
    private val calculations: ProfileCalculationService,
    private val scheduler: BiaProgressCoachScheduler,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val root = params.getString(KEY_REPORT)?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: buildFallbackReport(profileId)
        val profile = profiles.get(profileId) ?: error("Profilo non disponibile")
        val result = service.analyze(
        BiaAnalysisService.Report(
                inputPayload = buildPrompt(root, profileId, profile),
                measurementCount = root.getInt("measurementCount"),
            )
        )
        val payload = JSONObject()
            .put("classification", result.interpretation.classification)
            .put("verdict", result.interpretation.verdict)
            .put("whatIsHappening", result.interpretation.whatIsHappening)
            .put("fatLoss", result.interpretation.fatLoss)
            .put("objective", result.interpretation.objective)
            .put("caloriesAndMacros", result.interpretation.caloriesAndMacros)
            .put("nextCheck", result.interpretation.nextCheck)
            .put("reliability", result.interpretation.reliability)
            .put("reliabilityReason", result.interpretation.reliabilityReason)
            .put("comparisons", result.interpretation.comparisons.toJson { comparison ->
                JSONObject().put("indicator", comparison.indicator).put("previous", comparison.previous)
                    .put("current", comparison.current).put("difference", comparison.difference)
                    .put("interpretation", comparison.interpretation).put("reliability", comparison.reliability)
            })
            .put("historical", result.interpretation.historical.toJson { item -> JSONObject().put("period", item.period).put("summary", item.summary) })
            .put("scenarios", result.interpretation.scenarios.toJson { item ->
                JSONObject().put("label", item.label).put("targetBodyFat", item.targetBodyFat)
                    .put("targetWeight", item.targetWeight).put("fatToLose", item.fatToLose)
                    .put("assumption", item.assumption).put("reliability", item.reliability)
            })
            .put("positives", org.json.JSONArray(result.interpretation.positives))
            .put("monitor", org.json.JSONArray(result.interpretation.monitor))
            .put("actions", org.json.JSONArray(result.interpretation.actions))
            .put("safetyNote", result.interpretation.safetyNote)
            .put("question", result.interpretation.question ?: "")
            .put("agentValidation", result.interpretation.agentValidation)
            .put("provider", result.provider)
            .put("model", result.model)
            .toString()
        if (params.getBoolean(KEY_AUTOMATIC, false)) scheduler.onSuccess(profileId)
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}").build())
    } catch (error: Exception) {
        AiJobOutcome.Failure("BIA_PROGRESS_COACH_FAILED:${error.message ?: "Analisi BIA non disponibile"}")
    }

    private suspend fun buildPrompt(root: JSONObject, profileId: Long, profile: com.myfitai.app.data.local.entity.UserProfileEntity): String = buildString {
        val biaHistory = bia.all(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val bodyHistory = body.all(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val workoutHistory = workouts.all(profileId).first().sortedBy { it.startedAtEpochMillis }
        val snapshot = calculations.profileSnapshot(profileId)
        val planLines = plans.plans(profileId).first().mapNotNull { plan ->
            plans.loadLatestSnapshot(profileId, plan.weekStartEpochDay)?.let { snapshot ->
                snapshot.version.days.sortedBy { it.dateEpochDay }.joinToString(";") { day ->
                    "date=${day.dateEpochDay},kcal=${day.totalKcal ?: "?"},p=${day.proteinG ?: "?"},c=${day.carbsG ?: "?"},f=${day.fatG ?: "?"}"
                }
            }
        }
        appendLine("PROFILO_UTENTE:")
        appendLine("sex=${profile.biologicalSex ?: "?"}|birthDate=${profile.birthDateEpochDay ?: "?"}|heightCm=${profile.heightCm ?: "?"}|weightKg=${profile.currentWeightKg ?: "?"}|goal=${profile.goal ?: "?"}|activity=${profile.activityLevel ?: "?"}|wake=${profile.wakeTimeMinutes ?: "?"}|sleep=${profile.sleepTimeMinutes ?: "?"}|dietary=${profile.dietaryPreferencesJson ?: "?"}")
        appendLine("OBIETTIVO:${profile.goal ?: "?"}")
        appendLine("BIA_CORRENTE:${root.optJSONArray("current")?.toString() ?: "?"}")
        appendLine("STORICO_BIA:${root.optJSONArray("history")?.toString() ?: biaHistory.toPromptJson()}")
        appendLine("DELTA_PRECEDENTE:${root.optJSONArray("previousDelta")?.toString() ?: "?"}")
        appendLine("CONDIZIONI_MISURAZIONE:${root.optString("conditions", "?")}")
        appendLine("NOTE_UTENTE:${root.optString("notes", "?")}")
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

    private fun List<com.myfitai.app.data.local.entity.BiaMeasurementEntity>.toPromptJson(): String = joinToString(prefix = "[", postfix = "]") { row ->
        "{date=${row.measuredAtEpochMillis},weight=${row.weightKg ?: "?"},fat=${row.bodyFatPercent ?: "?"},lean=${row.leanMassKg ?: "?"},muscle=${row.muscleMassKg ?: "?"},water=${row.bodyWaterPercent ?: "?"},bmr=${row.bmrKcal ?: "?"}}"
    }.ifBlank { "?" }

    private fun <T> List<T>.toJson(mapper: (T) -> JSONObject): org.json.JSONArray = org.json.JSONArray().apply {
        forEach { put(mapper(it)) }
    }

    companion object {
        const val KEY_REPORT = "report"
        const val KEY_PAYLOAD = "payload"
        const val KEY_AUTOMATIC = "automatic"
    }

    private suspend fun buildFallbackReport(profileId: Long): JSONObject {
        val history = bia.all(profileId).first().sortedByDescending { it.measuredAtEpochMillis }
        val latest = history.firstOrNull() ?: error("Nessuna rilevazione BIA disponibile")
        val previous = history.getOrNull(1)
        fun values(item: com.myfitai.app.data.local.entity.BiaMeasurementEntity) = linkedMapOf<String, Float>().apply {
            item.weightKg?.let { put("weightKg", it) }; item.bodyFatPercent?.let { put("bodyFatPercent", it) }
            item.visceralFatLevel?.let { put("visceralFatLevel", it) }; item.muscleMassKg?.let { put("muscleMassKg", it) }
            item.skeletalMuscleKg?.let { put("skeletalMuscleKg", it) }; item.bodyWaterPercent?.let { put("bodyWaterPercent", it) }
        }
        val current = values(latest)
        val previousValues = previous?.let(::values).orEmpty()
        val delta = current.mapNotNull { (key, value) -> previousValues[key]?.let { key to (value - it) } }.toMap()
        return JSONObject().put("measurementCount", history.size).put("current", org.json.JSONArray().apply { current.forEach { (k, v) -> put(JSONObject().put("key", k).put("value", v)) } })
            .put("previousDelta", org.json.JSONArray().apply { delta.forEach { (k, v) -> put(JSONObject().put("key", k).put("value", v)) } })
            .put("history", org.json.JSONArray())
            .put("conditions", "?")
    }
}
