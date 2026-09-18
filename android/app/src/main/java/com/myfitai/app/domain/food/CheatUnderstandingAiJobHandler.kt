package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import org.json.JSONObject

class CheatUnderstandingAiJobHandler(private val service: CheatAdjustmentService) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val input = CheatAdjustmentService.Input(
            description = params.getString(KEY_DESCRIPTION).orEmpty(),
            quantityText = params.getString(KEY_QUANTITY),
            notes = params.getString(KEY_NOTES),
            occurredAtEpochMillis = params.getLong(KEY_OCCURRED_AT, 0L),
        )
        val result = service.analyze(input)
        val payload = JSONObject().put("understoodFood", result.understoodFood).put("kcal", result.estimate.kcal).put("proteinG", result.estimate.proteinG).put("carbsG", result.estimate.carbsG).put("fatG", result.estimate.fatG).put("confidence", result.estimate.confidence).put("notes", result.estimate.notes).put("provider", result.provider).put("model", result.model).toString()
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}").build())
    } catch (error: CheatAdjustmentService.AdjustmentException.NeedsInput) {
        AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
    } catch (error: Exception) {
        AiJobOutcome.Failure(error.message ?: "Valutazione sgarro non disponibile")
    }

    companion object { const val KEY_DESCRIPTION = "description"; const val KEY_QUANTITY = "quantity"; const val KEY_NOTES = "notes"; const val KEY_OCCURRED_AT = "occurred_at"; const val KEY_PAYLOAD = "payload" }
}
