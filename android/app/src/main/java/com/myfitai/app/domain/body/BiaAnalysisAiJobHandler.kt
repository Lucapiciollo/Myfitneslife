package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.ai.AiUserContext
import com.myfitai.app.data.repository.UserProfileRepository
import java.time.LocalDate
import org.json.JSONObject

class BiaAnalysisAiJobHandler(
    private val service: BiaAnalysisService,
    private val profiles: UserProfileRepository,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val root = JSONObject(params.getString(KEY_REPORT).orEmpty())
        val current = decodeValues(root.getJSONArray("current"))
        val deltas = decodeValues(root.getJSONArray("previousDelta"))
        val profile = profiles.get(profileId) ?: error("Profilo non disponibile")
        val userContext = AiUserContext.profileLine(profile, LocalDate.now(), current["weightKg"])
        val result = service.analyze(BiaAnalysisService.Report(current, deltas, root.getInt("measurementCount")), userContext)
        val payload = JSONObject()
            .put("summary", result.summary)
            .put("muscleStatus", result.muscleStatus)
            .put("doingWell", org.json.JSONArray(result.doingWell))
            .put("improve", org.json.JSONArray(result.improve))
            .put("provider", result.validation)
            .toString()
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, result.validation).build())
    } catch (error: Exception) {
        AiJobOutcome.Failure(error.message ?: "Analisi BIA non disponibile")
    }

    private fun decodeValues(array: org.json.JSONArray): Map<String, Float> = buildMap {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            put(item.getString("key"), item.getDouble("value").toFloat())
        }
    }

    companion object {
        const val KEY_REPORT = "report"
        const val KEY_PAYLOAD = "payload"
    }
}
