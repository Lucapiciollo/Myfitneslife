package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope

object NutritionPathContract {
    const val SCHEMA_NAME = "myfitai_nutrition_path_pipe_v1"
    const val PROTOCOL = "NP1\nR|path|confidence|reason\nA|path|confidence|reason\nC|code|shortExplanation\nV|1_or_0|notes"
    val schemaJson = AiCompactEnvelope.schemaJson(PROTOCOL)
    data class Suggestion(val path: String, val confidence: Float, val reason: String)
    data class Response(val recommendation: Suggestion, val alternatives: List<Suggestion>, val code: String, val explanation: String, val agentValid: Boolean)

    fun parse(json: String): Response {
        val lines = AiCompactEnvelope.data(json).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.firstOrNull() == "NP1") { "NP_HEADER_INVALID" }
        var recommendation: Suggestion? = null
        val alternatives = mutableListOf<Suggestion>()
        var code = ""
        var explanation = ""
        var agentValid = false
        lines.drop(1).forEach { line ->
            val fields = line.split('|')
            when (fields.firstOrNull()) {
                "R", "A" -> {
                    require(fields.size == 4) { "NP_SUGGESTION_INVALID" }
                    val suggestion = Suggestion(fields[1].trim(), fields[2].replace(',', '.').toFloatOrNull() ?: error("NP_CONFIDENCE_INVALID"), fields[3].trim())
                    if (fields[0] == "R") recommendation = suggestion else alternatives += suggestion
                }
                "C" -> { require(fields.size == 3) { "NP_REASON_INVALID" }; code = fields[1].trim(); explanation = fields[2].trim() }
                "V" -> { require(fields.size == 3 && fields[1] in setOf("0", "1")) { "NP_VALIDATION_INVALID" }; agentValid = fields[1] == "1" }
                else -> error("NP_RECORD_INVALID")
            }
        }
        return Response(requireNotNull(recommendation) { "NP_RECOMMENDATION_MISSING" }, alternatives.take(2), code, explanation, agentValid)
    }

    fun validateBusiness(
        response: Response,
        hasBia: Boolean = true,
        hasBodyMeasurements: Boolean = true,
    ): Result<Unit> = runCatching {
        require(response.agentValid) { "NP_AGENT_VALIDATION_FAILED" }
        require(response.recommendation.path in ALLOWED_PATHS) { "NP_PATH_INVALID" }
        require(response.recommendation.confidence in 0f..1f) { "NP_CONFIDENCE_INVALID" }
        if (!hasBia && !hasBodyMeasurements) {
            require(response.recommendation.confidence <= 0.70f) { "NP_CONFIDENCE_TOO_HIGH_FOR_PROFILE_ONLY" }
        }
        require(response.recommendation.reason.isNotBlank() && response.recommendation.reason.length <= 180) { "NP_REASON_TOO_LONG" }
        response.alternatives.forEach { require(it.path in ALLOWED_PATHS && it.confidence in 0f..1f && it.reason.isNotBlank()) { "NP_ALTERNATIVE_INVALID" } }
        require(response.code.length <= 50 && response.explanation.length <= 180) { "NP_EXPLANATION_TOO_LONG" }
    }

    val ALLOWED_PATHS = setOf("RECOMPOSITION", "WEIGHT_LOSS", "MAINTENANCE", "MUSCLE_GAIN", "PERFORMANCE")
}
