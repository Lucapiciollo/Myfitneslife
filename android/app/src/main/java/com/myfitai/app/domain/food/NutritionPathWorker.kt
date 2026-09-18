package com.myfitai.app.domain.food

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.notifications.NutritionPathNotification
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class NutritionPathWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getLong(KEY_PROFILE_ID, -1L)
        if (profileId <= 0) return Result.failure(workDataOf(KEY_ERROR to "Profilo non disponibile"))
        return runCatching {
            val data = AppDataContainer.get(applicationContext)
            val profile = data.userProfileRepository.get(profileId) ?: error("Profilo non disponibile")
            val snapshot = data.profileCalculationService.profileSnapshot(profileId) ?: error("Dati profilo non disponibili")
            val request = AiStructuredRequest(
                systemPrompt = "NutritionPathAgent. Scope solo alimentazione. Suggerisci un percorso e massimo due alternative. Nessuna diagnosi, nessuna modifica a calorie o macro. Testi in italiano. Protocollo: ${NutritionPathContract.PROTOCOL}",
                userPrompt = buildContext(profile.goal, profile.activityLevel, snapshot),
                schemaName = NutritionPathContract.SCHEMA_NAME,
                schemaJson = NutritionPathContract.schemaJson,
                maxOutputTokens = 650,
                thinkingBudget = 0,
            )
            var parsed: NutritionPathContract.Response? = null
            val validated = data.aiRuntimeService.execute(request, maxSchemaRetries = 2) { json -> runCatching {
                NutritionPathContract.parse(json).also { NutritionPathContract.validateBusiness(it).getOrThrow(); parsed = it }
            } }
            val result = parsed ?: NutritionPathContract.parse(validated.jsonText)
            val payload = JSONObject()
                .put("recommendation", JSONObject().put("path", result.recommendation.path).put("confidence", result.recommendation.confidence).put("reason", result.recommendation.reason))
                .put("alternatives", JSONArray().apply { result.alternatives.forEach { put(JSONObject().put("path", it.path).put("confidence", it.confidence).put("reason", it.reason)) } })
                .put("code", result.code).put("explanation", result.explanation).put("agentValid", result.agentValid).toString()
            NutritionPathNotification.show(applicationContext, profileId, inputData.getString(KEY_JOB_KEY).orEmpty())
            Result.success(workDataOf(KEY_PAYLOAD to payload, KEY_PROVIDER to "${validated.provider.name} · ${validated.model}"))
        }.getOrElse { Result.failure(workDataOf(KEY_ERROR to (it.message ?: "Suggerimento non disponibile"))) }
    }

    private fun buildContext(goal: String?, activity: String?, snapshot: com.myfitai.app.domain.calculation.ProfileCalculationService.Snapshot): String = buildString {
        appendLine("P:${goal ?: "?"}|${activity ?: "?"}")
        appendLine("BIA:${snapshot.latestBiaTimestamp ?: "?"}|${snapshot.latestWeightKg ?: "?"}|${snapshot.latestBodyFatPercent ?: "?"}|${snapshot.latestMuscleMassKg ?: "?"}")
        appendLine("BODY:${snapshot.latestBodyMeasurementTimestamp ?: "?"}|${snapshot.latestWaistCm ?: "?"}")
        appendLine("TREND:${snapshot.recompositionState.name}")
    }

    companion object { const val KEY_PROFILE_ID = "profile_id"; const val KEY_JOB_KEY = "job_key"; const val KEY_PAYLOAD = "payload"; const val KEY_PROVIDER = "provider"; const val KEY_ERROR = "error" }
}
