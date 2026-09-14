package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope
import org.json.JSONObject

/** Canonical compact response contract for the strictly nutrition-scoped advice agent. */
object NutritionAdviceContract {
    const val SCHEMA_NAME = "myfitai_nutrition_advice_pipe_v1"
    val schemaJson: String = AiCompactEnvelope.schemaJson(NutritionAdviceCompactContract.PROTOCOL)

    data class Suggestion(
        val title: String,
        val reason: String,
        val estimatedKcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
    )

    data class Response(
        val inScope: Boolean,
        val answer: String,
        val suggestions: List<Suggestion>,
        val assumptions: String,
        val agentValidation: NutritionPlanContract.AgentValidation,
    )

    fun parse(jsonText: String): Response {
        val root = JSONObject(jsonText)
        if (root.has("data")) return NutritionAdviceCompactContract.parse(jsonText)
        val suggestionsJson = root.getJSONArray("suggestions")
        val suggestions = buildList {
            for (i in 0 until suggestionsJson.length()) {
                val item = suggestionsJson.getJSONObject(i)
                add(Suggestion(item.getString("title").trim(), item.getString("reason").trim(), item.getInt("estimatedKcal"), item.getDouble("proteinG").toFloat(), item.getDouble("carbsG").toFloat(), item.getDouble("fatG").toFloat()))
            }
        }
        val agent = root.getJSONObject("agentValidation")
        return Response(root.getBoolean("inScope"), root.getString("answer").trim(), suggestions, root.getString("assumptions").trim(), NutritionPlanContract.AgentValidation(agent.getBoolean("valid"), agent.getString("notes").trim()))
    }

    fun validateBusiness(response: Response): Result<Unit> = runCatching {
        if (!response.inScope) {
            require(response.suggestions.isEmpty()) { "OUT_OF_SCOPE_WITH_SUGGESTIONS" }
            return@runCatching
        }
        require(response.answer.isNotBlank() && response.answer.length <= 180) { "INVALID_COMPACT_ANSWER" }
        require(response.suggestions.size == 5) { "EXPECTED_FIVE_SUGGESTIONS" }
        require(response.assumptions.length <= 120) { "ASSUMPTIONS_TOO_LONG" }
        response.suggestions.forEach { suggestion ->
            require(suggestion.title.isNotBlank() && suggestion.title.length <= 70) { "INVALID_SUGGESTION_TITLE" }
            require(suggestion.reason.isNotBlank() && suggestion.reason.length <= 120) { "INVALID_SUGGESTION_REASON" }
            require(suggestion.estimatedKcal > 0) { "INVALID_KCAL" }
            require(listOf(suggestion.proteinG, suggestion.carbsG, suggestion.fatG).all { it >= 0f && it.isFinite() }) { "INVALID_MACROS" }
        }
    }
}
