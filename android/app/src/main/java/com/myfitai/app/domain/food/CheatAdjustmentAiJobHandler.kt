package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import org.json.JSONObject

class CheatAdjustmentAiJobHandler(private val service: CheatAdjustmentService) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val input = CheatAdjustmentService.Input(
            description = params.getString(KEY_DESCRIPTION).orEmpty(),
            quantityText = params.getString(KEY_QUANTITY),
            notes = params.getString(KEY_NOTES),
            occurredAtEpochMillis = params.getLong(KEY_OCCURRED_AT, 0L),
        )
        val estimate = CheatAdjustmentContract.Estimate(
            kcal = params.getInt(KEY_KCAL, 0),
            proteinG = params.getFloat(KEY_PROTEIN, 0f),
            carbsG = params.getFloat(KEY_CARBS, 0f),
            fatG = params.getFloat(KEY_FAT, 0f),
            confidence = params.getString(KEY_CONFIDENCE).orEmpty(),
            notes = params.getString(KEY_ESTIMATE_NOTES).orEmpty(),
        )
        val understanding = CheatAdjustmentService.Understanding(
            understoodFood = params.getString(KEY_UNDERSTOOD).orEmpty(),
            estimate = estimate,
            provider = params.getString(KEY_AGENT_PROVIDER).orEmpty(),
            model = params.getString(KEY_AGENT_MODEL).orEmpty(),
            inputFingerprint = params.getString(KEY_FINGERPRINT).orEmpty(),
        )
        val result = service.registerAndAdapt(input, understanding)
        val payload = JSONObject().put("cheatId", result.cheatId).put("adapted", result.adapted).put("newVersionId", result.newVersionId ?: JSONObject.NULL).put("estimatedKcal", result.estimatedKcal ?: JSONObject.NULL).put("estimateSummary", result.estimateSummary).put("adaptationSummary", result.adaptationSummary).put("modifiedMeals", org.json.JSONArray(result.modifiedMeals)).toString()
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, understanding.provider).build())
    } catch (error: Exception) {
        AiJobOutcome.Failure(error.message ?: "Adattamento sgarro non riuscito")
    }

    companion object {
        const val KEY_DESCRIPTION = "description"; const val KEY_QUANTITY = "quantity"; const val KEY_NOTES = "notes"; const val KEY_OCCURRED_AT = "occurred_at"
        const val KEY_KCAL = "kcal"; const val KEY_PROTEIN = "protein"; const val KEY_CARBS = "carbs"; const val KEY_FAT = "fat"; const val KEY_CONFIDENCE = "confidence"; const val KEY_ESTIMATE_NOTES = "estimate_notes"; const val KEY_UNDERSTOOD = "understood"; const val KEY_AGENT_PROVIDER = "agent_provider"; const val KEY_AGENT_MODEL = "agent_model"; const val KEY_FINGERPRINT = "fingerprint"; const val KEY_PAYLOAD = "payload"
    }
}
