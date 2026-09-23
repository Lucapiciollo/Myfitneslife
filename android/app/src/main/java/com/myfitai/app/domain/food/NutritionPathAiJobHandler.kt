package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.ai.AiUserContext
import org.json.JSONArray
import org.json.JSONObject

class NutritionPathAiJobHandler(
    private val aiRuntime: AiRuntimeGateway,
    private val profiles: UserProfileRepository,
    private val calculations: ProfileCalculationService,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        val profile = profiles.get(profileId) ?: return AiJobOutcome.Failure("Profilo non disponibile")
        val snapshot = calculations.profileSnapshot(profileId) ?: return AiJobOutcome.Failure("Dati profilo non disponibili")
        val request = AiStructuredRequest(
            systemPrompt = """NutritionPathAgent. Consiglia il goal tecnico iniziale tra RECOMPOSITION, WEIGHT_LOSS, MAINTENANCE, MUSCLE_GAIN e PERFORMANCE.
${AiUserContext.INPUT_DESCRIPTION}
Scope solo alimentazione/fitness, nessuna diagnosi e nessuna modifica autonoma a calorie o macro: i numeri restano responsabilità del motore locale.
Usa esclusivamente i dati disponibili. La BIA e le circonferenze sono opzionali: se mancano non inventare body fat, massa muscolare o altri valori e riduci la confidence. BIA_ALL order is bmrKcal|fatMassKg|leanMassKg|bodyWaterKg|subcutaneousFatPct|boneMassKg|proteinPct|proteinKg|bodyAgeYears|bmi.
BIA order is timestamp|weightKg|bodyFatPct|muscleMassKg|skeletalMuscleKg|bodyWaterPct|visceralFat. BODY order is timestamp|chestCm|waistCm|abdomenCm|shouldersCm|glutesCm|hipsCm|armLeftCm|armRightCm|thighLeftCm|thighRightCm|calfLeftCm. `?` means unavailable.
Non usare BMI/peso da soli come criterio assoluto. Considera congiuntamente età/sesso/altezza/peso disponibili, attività e segnali corporei disponibili.
Se i dati sono limitati puoi comunque dare una raccomandazione prudente e alternative, esplicitando la minore qualità del contesto nel reason/code.
Il goal eventualmente già salvato è solo contesto storico e non deve obbligare la raccomandazione.
Suggerisci un percorso e massimo due alternative. Testi in italiano. Protocollo: ${NutritionPathContract.PROTOCOL}""".trimIndent(),
            userPrompt = buildString {
                appendLine("U:${AiUserContext.profileLine(profile, java.time.LocalDate.now(), snapshot.latestWeightKg)}")
                appendLine("LC:${AiUserContext.calculationLine(snapshot.calculation)}")
                appendLine("P:${profile.biologicalSex ?: "?"}|${profile.birthDateEpochDay ?: "?"}|${profile.heightCm ?: "?"}|${profile.currentWeightKg ?: "?"}|${profile.activityLevel ?: "?"}|${profile.goal ?: "?"}")
                val b = snapshot.biaMetrics
                appendLine("BIA:${snapshot.latestBiaTimestamp ?: "?"}|${b.weight.current ?: "?"}|${b.bodyFat.current ?: "?"}|${b.muscleMass.current ?: "?"}|${b.skeletalMuscle.current ?: "?"}|${b.bodyWater.current ?: "?"}|${b.visceralFat.current ?: "?"}")
                appendLine("BIA_ALL:${listOf(b.bmr, b.fatMass, b.leanMass, b.bodyWaterKg, b.subcutaneousFat, b.boneMass, b.proteinPercent, b.proteinKg, b.bodyAge, b.bmi).joinToString("|") { it.current?.toString() ?: "?" }}")
                val body = snapshot.bodyMetrics
                appendLine("BODY:${snapshot.latestBodyMeasurementTimestamp ?: "?"}|${bodyValues(body)}")
                appendLine("TREND:${snapshot.recompositionState.name}")
            },
            schemaName = NutritionPathContract.SCHEMA_NAME,
            schemaJson = NutritionPathContract.schemaJson,
            maxOutputTokens = 650,
            thinkingBudget = 0,
        )
        var parsed: NutritionPathContract.Response? = null
        val validated = aiRuntime.execute(request, maxSchemaRetries = 2) { json -> runCatching {
            NutritionPathContract.parse(json).also {
                NutritionPathContract.validateBusiness(
                    response = it,
                    hasBia = snapshot.latestBiaTimestamp != null,
                    hasBodyMeasurements = snapshot.latestBodyMeasurementTimestamp != null,
                ).getOrThrow()
                parsed = it
            }
        } }
        val result = parsed ?: NutritionPathContract.parse(validated.jsonText)
        val payload = JSONObject()
            .put("recommendation", JSONObject().put("path", result.recommendation.path).put("confidence", result.recommendation.confidence).put("reason", result.recommendation.reason))
            .put("alternatives", JSONArray().apply { result.alternatives.forEach { put(JSONObject().put("path", it.path).put("confidence", it.confidence).put("reason", it.reason)) } })
            .put("code", result.code)
            .put("explanation", result.explanation)
            .put("agentValid", result.agentValid)
            .put("hasBia", snapshot.latestBiaTimestamp != null)
            .put("hasBodyMeasurements", snapshot.latestBodyMeasurementTimestamp != null)
            .toString()
        return AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, "${validated.provider.name} · ${validated.model}").build())
    }

    private fun bodyValues(body: ProfileCalculationService.BodyMeasurementsSnapshot): String = listOf(
        body.chest, body.waist, body.abdomen, body.shoulders, body.glutes, body.hips,
        body.armLeft, body.armRight, body.thighLeft, body.thighRight, body.calfLeft, body.calfRight,
    ).joinToString("|") { it.current?.toString() ?: "?" }

    companion object { const val KEY_PAYLOAD = "payload" }
}
