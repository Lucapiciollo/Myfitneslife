package com.myfitai.app.domain.food

import org.json.JSONObject

/** Canonical compact response contract for the strictly nutrition-scoped advice agent. */
object NutritionAdviceContract {
    const val SCHEMA_NAME = "myfitai_nutrition_advice_v1"

    val schemaJson: String = JSONObject(
        """
        {
          "type":"object",
          "additionalProperties":false,
          "properties":{
            "inScope":{"type":"boolean"},
            "answer":{"type":"string"},
            "suggestions":{
              "type":"array",
              "maxItems":5,
              "items":{
                "type":"object",
                "additionalProperties":false,
                "properties":{
                  "title":{"type":"string"},
                  "reason":{"type":"string"},
                  "estimatedKcal":{"type":"integer"},
                  "proteinG":{"type":"number"},
                  "carbsG":{"type":"number"},
                  "fatG":{"type":"number"}
                },
                "required":["title","reason","estimatedKcal","proteinG","carbsG","fatG"]
              }
            },
            "assumptions":{"type":"string"},
            "agentValidation":{
              "type":"object",
              "additionalProperties":false,
              "properties":{"valid":{"type":"boolean"},"notes":{"type":"string"}},
              "required":["valid","notes"]
            }
          },
          "required":["inScope","answer","suggestions","assumptions","agentValidation"]
        }
        """.trimIndent()
    ).toString()

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
        val suggestionsJson = root.getJSONArray("suggestions")
        val suggestions = buildList {
            for (i in 0 until suggestionsJson.length()) {
                val item = suggestionsJson.getJSONObject(i)
                add(
                    Suggestion(
                        title = item.getString("title").trim(),
                        reason = item.getString("reason").trim(),
                        estimatedKcal = item.getInt("estimatedKcal"),
                        proteinG = item.getDouble("proteinG").toFloat(),
                        carbsG = item.getDouble("carbsG").toFloat(),
                        fatG = item.getDouble("fatG").toFloat(),
                    )
                )
            }
        }
        val agent = root.getJSONObject("agentValidation")
        return Response(
            inScope = root.getBoolean("inScope"),
            answer = root.getString("answer").trim(),
            suggestions = suggestions,
            assumptions = root.getString("assumptions").trim(),
            agentValidation = NutritionPlanContract.AgentValidation(
                valid = agent.getBoolean("valid"),
                notes = agent.getString("notes").trim(),
            ),
        )
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
