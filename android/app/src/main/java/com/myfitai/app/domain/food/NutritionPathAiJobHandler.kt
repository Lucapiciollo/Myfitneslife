package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.domain.calculation.ProfileCalculationService
import org.json.JSONArray
import org.json.JSONObject

class NutritionPathAiJobHandler(
    private val aiRuntime: AiRuntimeGateway,
    private val profiles: UserProfileRepository,
    private val calculations: ProfileCalculationService,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String): AiJobOutcome {
        val profile = profiles.get(profileId) ?: return AiJobOutcome.Failure("Profilo non disponibile")
        val snapshot = calculations.profileSnapshot(profileId) ?: return AiJobOutcome.Failure("Dati profilo non disponibili")
        val request = AiStructuredRequest(
            systemPrompt = "NutritionPathAgent. Scope solo alimentazione. Suggerisci un percorso e massimo due alternative. Nessuna diagnosi, nessuna modifica a calorie o macro. Testi in italiano. Protocollo: ${NutritionPathContract.PROTOCOL}",
            userPrompt = buildString {
                appendLine("P:${profile.goal ?: "?"}|${profile.activityLevel ?: "?"}")
                appendLine("BIA:${snapshot.latestBiaTimestamp ?: "?"}|${snapshot.latestWeightKg ?: "?"}|${snapshot.latestBodyFatPercent ?: "?"}|${snapshot.latestMuscleMassKg ?: "?"}")
                appendLine("BODY:${snapshot.latestBodyMeasurementTimestamp ?: "?"}|${snapshot.latestWaistCm ?: "?"}")
                appendLine("TREND:${snapshot.recompositionState.name}")
            },
            schemaName = NutritionPathContract.SCHEMA_NAME,
            schemaJson = NutritionPathContract.schemaJson,
            maxOutputTokens = 650,
            thinkingBudget = 0,
        )
        var parsed: NutritionPathContract.Response? = null
        val validated = aiRuntime.execute(request, maxSchemaRetries = 2) { json -> runCatching {
            NutritionPathContract.parse(json).also { NutritionPathContract.validateBusiness(it).getOrThrow(); parsed = it }
        } }
        val result = parsed ?: NutritionPathContract.parse(validated.jsonText)
        val payload = JSONObject()
            .put("recommendation", JSONObject().put("path", result.recommendation.path).put("confidence", result.recommendation.confidence).put("reason", result.recommendation.reason))
            .put("alternatives", JSONArray().apply { result.alternatives.forEach { put(JSONObject().put("path", it.path).put("confidence", it.confidence).put("reason", it.reason)) } })
            .put("code", result.code).put("explanation", result.explanation).put("agentValid", result.agentValid).toString()
        return AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, "${validated.provider.name} · ${validated.model}").build())
    }

    companion object { const val KEY_PAYLOAD = "payload" }
}
