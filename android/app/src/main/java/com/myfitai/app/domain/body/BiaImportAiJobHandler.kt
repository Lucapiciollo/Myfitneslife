package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.domain.ai.AiImageJobStore
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import org.json.JSONObject

class BiaImportAiJobHandler(private val service: BiaImportService, private val images: AiImageJobStore) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val result = service.import(images.read(params.getString(AiJobWorker.KEY_IMAGE_PATH).orEmpty()))
        val p = result.preview
        val payload = JSONObject().put("weightKg", p.weightKg ?: JSONObject.NULL).put("bodyFatPercent", p.bodyFatPercent ?: JSONObject.NULL).put("visceralFatLevel", p.visceralFatLevel ?: JSONObject.NULL).put("muscleMassKg", p.muscleMassKg ?: JSONObject.NULL).put("skeletalMuscleKg", p.skeletalMuscleKg ?: JSONObject.NULL).put("bodyWaterPercent", p.bodyWaterPercent ?: JSONObject.NULL).put("bmrKcal", p.bmrKcal ?: JSONObject.NULL).put("measuredAtEpochMillis", p.measuredAtEpochMillis ?: JSONObject.NULL).put("confidence", p.confidence).put("notes", p.notes).put("provider", result.provider).put("model", result.model).toString()
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}").build())
    } catch (error: Exception) { AiJobOutcome.Failure(error.message ?: "Import BIA non riuscito") }
    companion object { const val KEY_PAYLOAD = "payload" }
}
