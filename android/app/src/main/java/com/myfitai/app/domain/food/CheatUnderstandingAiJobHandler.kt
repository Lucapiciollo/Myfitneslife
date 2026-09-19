package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.ai.AiImageJobStore
import com.myfitai.app.ai.AiImageInput
import org.json.JSONObject

class CheatUnderstandingAiJobHandler(private val service: CheatAdjustmentService, private val imageStore: AiImageJobStore) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val image = params.getString(AiJobWorker.KEY_IMAGE_PATH)?.let(imageStore::read)
        val input = CheatAdjustmentService.Input(
            description = params.getString(KEY_DESCRIPTION).orEmpty(),
            quantityText = params.getString(KEY_QUANTITY),
            notes = params.getString(KEY_NOTES),
            occurredAtEpochMillis = params.getLong(KEY_OCCURRED_AT, 0L),
            labelImage = image,
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
