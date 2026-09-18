package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import org.json.JSONArray
import org.json.JSONObject

class MealAlternativesAiJobHandler(private val service: MealAlternativeService) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val parts = jobKey.split('-')
        val week = parts.getOrNull(0)?.toLongOrNull() ?: return AiJobOutcome.Failure("Settimana non valida")
        val day = parts.getOrNull(1)?.toLongOrNull() ?: return AiJobOutcome.Failure("Giorno non valido")
        val mealId = parts.getOrNull(2)?.toLongOrNull() ?: return AiJobOutcome.Failure("Pasto non valido")
        val result = service.generate(profileId, week, day, mealId)
        val payload = JSONObject().apply {
            put("planId", result.planId)
            put("sourceVersionId", result.sourceVersionId)
            put("weekStartEpochDay", result.weekStartEpochDay)
            put("dayEpochDay", result.dayEpochDay)
            put("mealId", result.mealId)
            put("mealType", result.mealType)
            put("mealTimeMinutes", result.mealTimeMinutes ?: JSONObject.NULL)
            put("targetKcal", result.targetKcal)
            put("provider", result.provider)
            put("model", result.model)
            put("items", JSONArray().apply {
                result.items.forEach { item ->
                    put(JSONObject().apply {
                        put("title", item.title)
                        put("kcal", item.kcal)
                        put("proteinG", item.proteinG)
                        put("carbsG", item.carbsG)
                        put("fatG", item.fatG)
                        put("preparation", item.preparation)
                        put("reason", item.reason)
                        put("ingredients", JSONArray().apply {
                            item.ingredients.forEach { ingredient ->
                                put(JSONObject().apply {
                                    put("name", ingredient.name)
                                    put("quantity", ingredient.quantity)
                                    put("unit", ingredient.unit)
                                    put("displayDose", ingredient.displayDose)
                                    put("weightState", ingredient.weightState)
                                    put("nutritionConfidence", ingredient.nutritionConfidence)
                                    put("category", ingredient.category)
                                })
                            }
                        })
                    })
                }
            })
        }.toString()
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}").build())
    } catch (error: MealAlternativeService.AlternativeException) {
        AiJobOutcome.Failure(error.message ?: "Alternative non disponibili")
    }

    companion object { const val KEY_PAYLOAD = "payload" }
}
