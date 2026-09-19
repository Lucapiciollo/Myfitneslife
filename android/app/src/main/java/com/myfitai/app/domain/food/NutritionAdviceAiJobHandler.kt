package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import org.json.JSONArray
import org.json.JSONObject

class NutritionAdviceAiJobHandler(private val service: NutritionAdviceService) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val question = params.getString(KEY_QUESTION).orEmpty()
        if (question.isBlank()) return AiJobOutcome.Failure("Domanda mancante")
        val result = service.ask(question)
        val payload = JSONObject().put("question", question).put("answer", result.answer).put("assumptions", result.assumptions).put("providerLabel", result.providerLabel ?: "").put("accepted", result.accepted).put("suggestions", JSONArray().apply {
            result.suggestions.forEach { suggestion -> put(JSONObject().put("title", suggestion.title).put("reason", suggestion.reason).put("estimatedKcal", suggestion.estimatedKcal).put("proteinG", suggestion.proteinG).put("carbsG", suggestion.carbsG).put("fatG", suggestion.fatG).put("foods", JSONArray(suggestion.foods))) }
        }).toString()
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, result.providerLabel.orEmpty()).build())
    } catch (error: Exception) {
        AiJobOutcome.Failure(error.message ?: "Consiglio non disponibile")
    }

    companion object { const val KEY_QUESTION = "nutrition_advice_question"; const val KEY_PAYLOAD = "payload" }
}
