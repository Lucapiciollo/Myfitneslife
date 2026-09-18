package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope
import org.json.JSONObject

object NutritionPathContract {
    const val SCHEMA_NAME = "myfitai_nutrition_path_pipe_v1"
    const val PROTOCOL = "NP1\nR|path|confidence|reason\nA|path|confidence|reason\nC|code|shortExplanation\nV|1_or_0|notes"
    val schemaJson: String = AiCompactEnvelope.schemaJson(PROTOCOL)

    data class Suggestion(val path: String, val confidence: Float, val reason: String)
    data class Response(val recommendation: Suggestion, val alternatives: List<Suggestion>, val code: String, val explanation: String, val agentValid: Boolean)

    fun parse(json: String): Response {
        val lines = AiCompactEnvelope.data(json).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "NP1") { "NP_HEADER_INVALID" }
        var recommendation: Suggestion? = null
        val alternatives = mutableListOf<Suggestion>()
        var code = ""
        var explanation = ""
        var valid = false
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "R", "A" -> {
                    require(p.size == 4) { "NP_SUGGESTION_INVALID" }
                    val suggestion = Suggestion(p[1].trim(), p[2].replace(',', '.').toFloatOrNull() ?: error("NP_CONFIDENCE_INVALID"), p[3].trim())
                    if (p[0] == "R") recommendation = suggestion else alternatives += suggestion
                }
                "C" -> { require(p.size == 3) { "NP_REASON_INVALID" }; code = p[1].trim(); explanation = p[2].trim() }
                "V" -> { require(p.size == 3 && p[1] in setOf("0", "1")) { "NP_VALIDATION_INVALID" }; valid = p[1] == "1" }
                else -> error("NP_RECORD_INVALID")
            }
        }
        return Response(requireNotNull(recommendation) { "NP_RECOMMENDATION_MISSING" }, alternatives.take(2), code, explanation, valid)
    }

    fun validateBusiness(response: Response): Result<Unit> = runCatching {
        require(response.recommendation.path in ALLOWED_PATHS) { "NP_PATH_INVALID" }
        require(response.recommendation.confidence in 0f..1f) { "NP_CONFIDENCE_INVALID" }
        require(response.recommendation.reason.isNotBlank() && response.recommendation.reason.length <= 180) { "NP_REASON_TOO_LONG" }
        response.alternatives.forEach { alternative ->
            require(alternative.path in ALLOWED_PATHS && alternative.confidence in 0f..1f && alternative.reason.isNotBlank()) { "NP_ALTERNATIVE_INVALID" }
        }
        require(response.code.length <= 50 && response.explanation.length <= 180) { "NP_EXPLANATION_TOO_LONG" }
    }

    val ALLOWED_PATHS = setOf("RECOMPOSITION", "WEIGHT_LOSS", "MAINTENANCE", "MUSCLE_GAIN", "PERFORMANCE")
}
