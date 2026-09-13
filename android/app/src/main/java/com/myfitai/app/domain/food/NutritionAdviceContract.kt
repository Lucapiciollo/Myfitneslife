package com.myfitai.app.domain.food

import org.json.JSONObject

/** Canonical contract for the nutrition-only advice agent. */
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
                  "estimatedKcal":{"type":["integer","null"]},
                  "proteinG":{"type":["number","null"]},
                  "carbsG":{"type":["number","null"]},
                  "fatG":{"type":["number","null"]}
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
        val estimatedKcal: Int?,
        val proteinG: Float?,
        val carbsG: Float?,
        val fatG: Float?,
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
                        estimatedKcal = item.optIntOrNull("estimatedKcal"),
                        proteinG = item.optDoubleOrNull("proteinG")?.toFloat(),
                        carbsG = item.optDoubleOrNull("carbsG")?.toFloat(),
                        fatG = item.optDoubleOrNull("fatG")?.toFloat(),
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
        require(response.answer.isNotBlank()) { "EMPTY_NUTRITION_ANSWER" }
        require(response.suggestions.size <= 5) { "TOO_MANY_SUGGESTIONS" }
        response.suggestions.forEach { suggestion ->
            require(suggestion.title.isNotBlank() && suggestion.reason.isNotBlank()) { "INVALID_SUGGESTION" }
            require(suggestion.estimatedKcal == null || suggestion.estimatedKcal > 0) { "INVALID_KCAL" }
            require(listOf(suggestion.proteinG, suggestion.carbsG, suggestion.fatG).all { it == null || (it >= 0f && it.isFinite()) }) { "INVALID_MACROS" }
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
}
